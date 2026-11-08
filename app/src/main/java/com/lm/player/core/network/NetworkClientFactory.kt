package com.lm.player.core.network

import android.content.Context
import android.os.Build
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.conscrypt.Conscrypt
import java.security.Security
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

object NetworkClientFactory {
    private const val TAG = "NetworkClientFactory"

    @Volatile
    private var isConscryptInstalled = false

    /**
     * 为老旧系统 (Android 6.0 ~ 9.0, API 23 ~ 28) 强力注入 Conscrypt 安全引擎，
     * 补齐现代 TLS 1.3、ECC 加密套件以及 Let's Encrypt 等现代根证书信任链，
     * 根治 HTTPS/SSL 握手失败异常 (SSLHandshakeException)。
     */
    fun installSecurityProvider() {
        if (isConscryptInstalled) return
        synchronized(this) {
            if (isConscryptInstalled) return
            try {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    val conscryptProvider = Conscrypt.newProvider()
                    Security.insertProviderAt(conscryptProvider, 1)
                    Log.i(TAG, "Successfully installed Conscrypt TLS 1.3 security provider on API ${Build.VERSION.SDK_INT}")
                }
                isConscryptInstalled = true
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to insert Conscrypt security provider", e)
            }
        }
    }

    @Volatile
    private var sharedClient: OkHttpClient? = null

    @Volatile
    private var sharedImageClient: OkHttpClient? = null

    @Volatile
    private var activeAuthToken: String = ""

    private val authTokenHosts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun setActiveAuthToken(token: String, serverHost: String? = null) {
        if (token.isNotBlank()) {
            activeAuthToken = token.trim()
            if (!serverHost.isNullOrBlank()) {
                val cleanHost = serverHost.removePrefix("http://").removePrefix("https://").substringBefore("/").substringBefore(":")
                if (cleanHost.isNotBlank()) {
                    authTokenHosts.add(cleanHost.lowercase())
                }
            }
        }
    }

    fun isTokenHostAllowed(host: String): Boolean {
        if (authTokenHosts.isEmpty()) return true
        val target = host.lowercase()
        return authTokenHosts.any { allowed -> target == allowed || target.endsWith(".$allowed") }
    }

    fun getActiveAuthToken(): String = activeAuthToken

    /**
     * 发往**已登记的柠檬服务器主机**的请求：把 URL 里的 `token=` 参数移到 Authorization 头里。
     * 旧版本把 Token 拼在播放/封面地址里并存进了数据库，这里保证它不会再出现在请求行（服务端访问日志、
     * 反向代理日志）里。优先使用当前有效的 Token，没有时才用 URL 里的旧值。
     * 未登记任何主机时不处理（避免误删第三方地址里同名的 token 参数）。
     */
    private fun moveTokenParamToHeader(request: okhttp3.Request, reqBuilder: okhttp3.Request.Builder): Boolean {
        val tokenParam = request.url.queryParameter("token")?.takeIf { it.isNotBlank() } ?: return false
        if (authTokenHosts.isEmpty() || !isTokenHostAllowed(request.url.host)) return false
        reqBuilder.url(request.url.newBuilder().removeAllQueryParameters("token").build())
        if (request.header("Authorization").isNullOrBlank()) {
            val bearer = activeAuthToken.takeIf { it.isNotBlank() } ?: tokenParam
            reqBuilder.header("Authorization", "Bearer $bearer")
        }
        return true
    }

    fun clearActiveAuthToken() {
        activeAuthToken = ""
        authTokenHosts.clear()
    }

    /**
     * 会话失效（401）时的重新登录回调：返回该主机的新 Token；无法重登（无保存密码 / 密码错误 / 网络失败）返回 null。
     * 由 LemonMusicProtocol.installSessionReauth 在 Application 启动时注册。
     */
    fun interface ReauthProvider {
        fun refreshToken(host: String, failedToken: String?): String?
    }

    @Volatile
    var reauthProvider: ReauthProvider? = null

    /**
     * 柠檬服务端会话过期（30 天未活跃、改密码、恢复备份、重建容器等）后所有接口都会 401。
     * 此前客户端没有任何 401 处理，只能删掉服务器重新添加。这里对**已登记的服务器主机**的 /api/ 请求
     * 做一次自动重登并重试；以下情况不处理，直接把 401 交还调用方：
     * - /api/auth/ 下的接口（登录、会话校验本身，避免递归）；
     * - 带 ?ticket= 的流媒体地址（服务端只认 ticket，换 Bearer 也无效，应由播放层重新取链）；
     * - 非本服务器主机（第三方 CDN 等）；
     * - 已经重试过一次的请求。
     */
    private val sessionAuthenticator: okhttp3.Authenticator = object : okhttp3.Authenticator {
        override fun authenticate(route: okhttp3.Route?, response: okhttp3.Response): okhttp3.Request? {
            val request = response.request
            if (response.priorResponse != null) return null
            val provider = reauthProvider ?: return null
            val path = request.url.encodedPath
            if (!path.startsWith("/api/") || path.startsWith("/api/auth/") || path.startsWith("/api/v1/auth/")) {
                return null
            }
            if (request.url.queryParameter("ticket") != null) return null
            // 注意 isTokenHostAllowed 在白名单为空时放行所有主机，这里必须显式要求已登记
            if (authTokenHosts.isEmpty() || !isTokenHostAllowed(request.url.host)) return null
            val failedToken = request.header("Authorization")
                ?.removePrefix("Bearer ")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: request.url.queryParameter("token")
            val fresh = runCatching { provider.refreshToken(request.url.host, failedToken) }.getOrNull()
            if (fresh.isNullOrBlank() || fresh == failedToken) return null
            return request.newBuilder()
                .header("Authorization", "Bearer $fresh")
                .build()
        }
    }

    fun createOkHttpClient(context: Context): OkHttpClient {
        return getSharedClient(context)
    }

    @Synchronized
    fun getSharedClient(context: Context): OkHttpClient {
        sharedClient?.let { return it }

        installSecurityProvider()

        val appContext = context.applicationContext
        val cacheDir = appContext.cacheDir.resolve("okhttp_http_cache")
        val cache = try {
            // HTTP 磁盘缓存 16MB（原 64MB）：音频流接口已强制 FORCE_NETWORK 绕过该缓存，
            // 其余接口（JSON/图片）收益有限，没必要占 64MB 磁盘配额
            okhttp3.Cache(cacheDir, 16L * 1024 * 1024)
        } catch (_: Exception) {
            null
        }

        val pool = okhttp3.ConnectionPool(32, 5, TimeUnit.MINUTES)
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 16
        }

        val builder = OkHttpClient.Builder()
            .connectionPool(pool)
            .dispatcher(dispatcher)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // **整个请求的总时长上限**（含连接、写入、服务端处理与读取）。
            //
            // connect/read/write 三个超时都是「单次 IO 操作」的语义，不是「请求总时长」：
            // 只要服务端持续慢速吐数据，readTimeout 就永远不会触发，请求可以拖到几分钟甚至更久。
            // 下载收尾阶段会同步拉取【封面图】与【歌词】，这两次请求此前没有任何总时长约束 ——
            // 一旦对方响应缓慢，下载进度条就会在 100% 停留很久（用户反复反馈的现象之一）。
            //
            // 300 秒足以覆盖整库同步这类大响应（readTimeout 仍是 60 秒，
            // 正常情况下单次读取不会超时），只用来兜住「长时间挂死」的极端情况。
            .callTimeout(300, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // 会话过期 401 → 用本地保存的密码自动重登一次并重试（见 sessionAuthenticator）
            .authenticator(sessionAuthenticator)

        if (cache != null) {
            builder.cache(cache)
        }

        // 本地目录网关（必须最先执行）：目录类接口本地加速 + 服务器离线熔断，见 LocalCatalogGateway
        builder.addInterceptor(com.lm.player.core.source.catalog.LocalCatalogGateway)

        // 统一注入现代主流 User-Agent 与自动补齐柠檬服务端 Authorization 头
        builder.addInterceptor { chain ->
            val request = chain.request()
            val reqBuilder = request.newBuilder()
            if (request.header("User-Agent").isNullOrBlank()) {
                reqBuilder.header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
            }
            val encodedPath = request.url.encodedPath
            // 自动为第三方音乐平台 CDN 音频直链补齐防盗链 Referer / Origin 头
            val cdnPlatform = MusicCdnHeaders.guessSourceFromUrl(request.url.toString())
            if (cdnPlatform.isNotBlank() && request.header("Referer").isNullOrBlank()) {
                val cdnHeaders = MusicCdnHeaders.buildHeaders(cdnPlatform)
                cdnHeaders.forEach { (k, v) ->
                    if (request.header(k).isNullOrBlank()) {
                        reqBuilder.header(k, v)
                    }
                }
            }
            // 音频流式接口绕过 OkHttp 磁盘缓存（交由 Media3 SimpleCache 统一管理），避免 206 Range 截断冲突
            if (encodedPath.contains("/api/play/")) {
                reqBuilder.cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
            }
            // 若 URL 中携带 token 参数或访问柠檬服务端 API 且未显式指定 Authorization 请求头，自动补充 Bearer Token
            // 严格做 Host 白名单核验，避免把柠檬服务端 Bearer Token 泄露给第三方 /api/ 接口 (TV-P0-01)
            val tokenParam = request.url.queryParameter("token")
            if (moveTokenParamToHeader(request, reqBuilder)) {
                // 已把 URL 中的 token 移到请求头
            } else if (request.header("Authorization").isNullOrBlank()) {
                if (!tokenParam.isNullOrBlank() && isTokenHostAllowed(request.url.host)) {
                    reqBuilder.header("Authorization", "Bearer $tokenParam")
                } else if (activeAuthToken.isNotBlank() && encodedPath.startsWith("/api/") && isTokenHostAllowed(request.url.host)) {
                    reqBuilder.header("Authorization", "Bearer $activeAuthToken")
                }
            }
            val sampleLatency = encodedPath.startsWith("/api/") &&
                !encodedPath.contains("/api/play/local") &&
                !encodedPath.contains("/api/play/proxy") &&
                !encodedPath.contains("/api/play/local-ape") &&
                !encodedPath.contains("/api/download/") &&
                !encodedPath.contains("/api/library/") &&
                !encodedPath.contains("/api/source/health/") &&
                !encodedPath.contains("/api/tag/")
            val startedAt = System.currentTimeMillis()
            try {
                val response = chain.proceed(reqBuilder.build())
                val elapsed = System.currentTimeMillis() - startedAt
                if (sampleLatency) {
                    ServerLatencyMonitor.record(
                        host = request.url.host,
                        elapsedMs = elapsed,
                        success = response.isSuccessful
                    )
                    // 反代 502/503/504 或返回 HTML 页面（柠檬服务被删、反代还在）说明柠檬服务本身已不可用：
                    // 上报失败，触发 /api/health 语义探活
                    val respType = response.header("Content-Type").orEmpty().lowercase()
                    val notLemon = response.code in 502..504 || respType.contains("text/html")
                    com.lm.player.core.source.SourceAccelerationController.onServerSample(
                        host = request.url.host,
                        elapsedMs = elapsed,
                        success = !notLemon
                    )
                }
                response
            } catch (e: java.io.IOException) {
                val elapsed = System.currentTimeMillis() - startedAt
                if (sampleLatency) {
                    ServerLatencyMonitor.record(
                        host = request.url.host,
                        elapsedMs = elapsed,
                        success = false
                    )
                    com.lm.player.core.source.SourceAccelerationController.onServerSample(
                        host = request.url.host,
                        elapsedMs = elapsed,
                        success = false
                    )
                }
                throw e
            }
        }

        // API 23~28 专属 SSLContext 配置
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            try {
                val conscryptProvider = Conscrypt.newProvider()
                val sslContext = SSLContext.getInstance("TLS", conscryptProvider).apply {
                    init(null, null, null)
                }
                val trustManager: X509TrustManager = Conscrypt.getDefaultX509TrustManager()
                builder.sslSocketFactory(sslContext.socketFactory, trustManager)
            } catch (e: Exception) {
                Log.w(TAG, "Using platform default SSL engine as fallback", e)
            }
        }

        val client = builder.build()
        sharedClient = client
        return client
    }

    /**
     * 专为 Coil 图片加载打造的高并发连接池 OkHttpClient
     * - 针对 4K/2K 大屏和车机横屏高密度卡片群优化
     * - 自动替换 kwcdn.kuwo.cn 为 img1.kuwo.cn (绕过 HTTPS 514 频控)
     * - 自动追加 CDN 轻量切图参数与对应平台的防盗链 Referer
     */
    @Synchronized
    fun createImageOkHttpClient(context: Context): OkHttpClient {
        sharedImageClient?.let { return it }

        installSecurityProvider()

        val pool = okhttp3.ConnectionPool(64, 5, java.util.concurrent.TimeUnit.MINUTES)
        val dispatcher = okhttp3.Dispatcher().apply {
            // 流畅模式 (第二批)：老设备上并发请求过多会与界面线程抢 CPU，降到 16 / 单主机 8 (启动时生效)
            val smooth = com.lm.player.core.designsystem.theme.SmoothMode.activeAtStartup
            maxRequests = if (smooth) 16 else 128
            maxRequestsPerHost = if (smooth) 8 else 32
        }

        val client = OkHttpClient.Builder()
            .connectionPool(pool)
            .dispatcher(dispatcher)
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            // 服务器封面 /api/tag/cover 同样会因会话过期 401，与共享客户端共用自动重登逻辑
            .authenticator(sessionAuthenticator)
            .addInterceptor { chain ->
                val origReq = chain.request()
                var urlStr = origReq.url.toString()

                // 1. 酷我 kwcdn 在 HTTPS 下会触发 514 频控重定向，替换为 img1 节点
                if (urlStr.contains("kwcdn.kuwo.cn", ignoreCase = true)) {
                    urlStr = urlStr.replace(Regex("""(?i)(?:[a-zA-Z0-9_\-]+\.)*kwcdn\.kuwo\.cn"""), "img1.kuwo.cn")
                    urlStr = urlStr.replace("img1.img1.kuwo.cn", "img1.kuwo.cn")
                }
                if (urlStr.startsWith("http://img1.kuwo.cn/")) {
                    urlStr = "https://" + urlStr.substring("http://".length)
                }

                // 2. 网易云图片自动追加轻量级 CDN 缩略图参数
                if (urlStr.contains("music.126.net") && !urlStr.contains("param=")) {
                    urlStr += if (urlStr.contains("?")) "&param=400y400" else "?param=400y400"
                }

                val reqBuilder = origReq.newBuilder().url(urlStr)

                // 3. 自动注入对应平台的防盗链 Referer 请求头
                val cdnPlatform = MusicCdnHeaders.guessSourceFromUrl(urlStr)
                if (cdnPlatform.isNotBlank() && origReq.header("Referer").isNullOrBlank()) {
                    val headers = MusicCdnHeaders.buildHeaders(cdnPlatform)
                    headers.forEach { (k, v) ->
                        if (origReq.header(k).isNullOrBlank()) {
                            reqBuilder.header(k, v)
                        }
                    }
                }

                // 4. 若图片请求指向柠檬音乐服务器 (/api/tag/cover, /api/cover 等)，自动注入服务端鉴权 Bearer Token，杜绝 401 Unauthorized 导致歌曲封面加载失败
                val tokenParam = origReq.url.queryParameter("token")
                if (moveTokenParamToHeader(origReq, reqBuilder)) {
                    // 已把 URL 中的 token 移到请求头
                } else if (origReq.header("Authorization").isNullOrBlank()) {
                    if (!tokenParam.isNullOrBlank() && isTokenHostAllowed(origReq.url.host)) {
                        reqBuilder.header("Authorization", "Bearer $tokenParam")
                    } else if (activeAuthToken.isNotBlank() && isTokenHostAllowed(origReq.url.host)) {
                        reqBuilder.header("Authorization", "Bearer $activeAuthToken")
                    }
                }

                chain.proceed(reqBuilder.build())
            }
            .build()

        sharedImageClient = client
        return client
    }
}
