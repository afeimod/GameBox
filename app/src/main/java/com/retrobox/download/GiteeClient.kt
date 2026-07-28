package com.retrobox.download

import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Gitee 仓库内容项
 *
 * @property name        文件 / 目录名
 * @property path        仓库内相对路径
 * @property type        类型："file" 或 "dir"
 * @property size        文件大小（字节）
 * @property url         API 访问地址
 * @property downloadUrl 原始文件下载地址（可能为空）
 */
data class GiteeContent(
    @SerializedName("name")
    val name: String = "",
    @SerializedName("path")
    val path: String = "",
    @SerializedName("type")
    val type: String = "",
    @SerializedName("size")
    val size: Long = 0L,
    @SerializedName("url")
    val url: String = "",
    @SerializedName("download_url")
    val download_url: String? = null
)

/**
 * Gitee API v5 服务接口（基于 Retrofit）
 */
interface GiteeApiService {

    /**
     * 获取仓库指定路径下的文件列表（支持分页）
     * API: https://gitee.com/api/v5/repos/{owner}/{repo}/contents/{path}
     */
    @GET("repos/{owner}/{repo}/contents/{path}")
    suspend fun getContents(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path", encoded = true) path: String,
        @Query("ref") ref: String? = null,
        @Query("page") page: Int = 1,
        @Query("per_page") perPage: Int = 20
    ): List<GiteeContent>
}

/**
 * Gitee API 客户端
 *
 * 基于 OkHttp + Retrofit，提供：
 * - 获取指定仓库路径下的文件列表（支持分页）
 * - 下载原始文件到本地（带进度回调）
 *
 * @property owner  仓库所属用户 / 组织
 * @property repo   仓库名
 * @property branch 分支名（默认 master）
 * @property token  私有令牌（可选，用于私有仓库或提升限流）
 */
class GiteeClient(
    private val owner: String,
    private val repo: String,
    private val branch: String = "master",
    private val token: String? = null
) {
    companion object {
        // API 基地址
        private const val API_BASE_URL = "https://gitee.com/api/v5/"
        // 原始文件下载基地址
        private const val RAW_BASE_URL = "https://gitee.com/"
    }

    /** OkHttp 客户端（含鉴权拦截器） */
    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                if (!token.isNullOrEmpty()) {
                    builder.addHeader("Authorization", "token $token")
                }
                chain.proceed(builder.build())
            }
            .build()
    }

    /** Retrofit API 服务 */
    private val apiService: GiteeApiService by lazy {
        Retrofit.Builder()
            .baseUrl(API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GiteeApiService::class.java)
    }

    /**
     * 获取指定路径下的文件列表（单页）
     *
     * @param path    仓库内目录路径
     * @param page    页码，从 1 开始
     * @param perPage 每页数量
     */
    suspend fun listFiles(path: String, page: Int = 1, perPage: Int = 20): List<GiteeContent> {
        return apiService.getContents(owner, repo, path, branch, page, perPage)
    }

    /**
     * 分页获取全部文件列表（自动翻页直到取完）
     *
     * @param path    仓库内目录路径
     * @param perPage 每页数量
     */
    suspend fun listAllFiles(path: String, perPage: Int = 50): List<GiteeContent> {
        val result = mutableListOf<GiteeContent>()
        var page = 1
        while (true) {
            val items = apiService.getContents(owner, repo, path, branch, page, perPage)
            if (items.isEmpty()) break
            result.addAll(items)
            // 不足一页说明已是最后一页
            if (items.size < perPage) break
            page++
        }
        return result
    }

    /**
     * 获取单个文件 / 目录的内容信息
     */
    suspend fun getContent(path: String): GiteeContent? {
        return apiService.getContents(owner, repo, path, branch, 1, 1).firstOrNull()
    }

    /**
     * 下载原始文件到本地
     * 下载地址: https://gitee.com/{owner}/{repo}/raw/{branch}/{path}
     *
     * @param remotePath 仓库内文件路径
     * @param destPath   本地保存路径
     * @param onProgress 进度回调（已接收字节, 总字节；总字节未知时为 -1）
     * @return 本地文件路径
     */
    suspend fun downloadFile(
        remotePath: String,
        destPath: String,
        onProgress: ((received: Long, total: Long) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        val url = buildRawUrl(remotePath)
        val request = Request.Builder().url(url).apply {
            if (!token.isNullOrEmpty()) {
                addHeader("Authorization", "token $token")
            }
        }.build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw RuntimeException("下载失败: HTTP ${response.code}")
        }
        val body = response.body ?: throw RuntimeException("下载失败: 响应体为空")
        val total = body.contentLength()

        val destFile = File(destPath)
        destFile.parentFile?.mkdirs()

        body.byteStream().use { input ->
            FileOutputStream(destFile).use { output ->
                val buffer = ByteArray(8 * 1024)
                var received = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    received += read
                    onProgress?.invoke(received, if (total > 0) total else -1L)
                }
            }
        }
        destPath
    }

    /** 构建原始文件下载 URL */
    fun buildRawUrl(remotePath: String): String {
        val safePath = remotePath.trimStart('/')
        return "$RAW_BASE_URL$owner/$repo/raw/$branch/$safePath"
    }

    /** 构建 API 访问 URL */
    fun buildApiUrl(path: String): String {
        val safePath = path.trimStart('/')
        return "${API_BASE_URL}repos/$owner/$repo/contents/$safePath"
    }
}
