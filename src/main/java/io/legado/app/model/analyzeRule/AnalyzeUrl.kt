package io.legado.app.model.analyzeRule

import javax.script.SimpleBindings
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppConst.SCRIPT_ENGINE
import io.legado.app.constant.AppConst.UA_NAME
import io.legado.app.constant.AppPattern.JS_PATTERN
import io.legado.app.constant.AppPattern.dataUriRegex
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.exception.ConcurrentException
import io.legado.app.help.CacheManager
import io.legado.app.help.JsExtensions
import io.legado.app.help.http.*
import io.legado.app.utils.*
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.URLEncoder
import java.util.regex.Pattern
import io.legado.app.model.DebugLog

/**
 * Created by GKF on 2018/1/24.
 * 搜索URL规则解析
 */
class AnalyzeUrl(
    val mUrl: String,
    val key: String? = null,
    val page: Int? = null,
    val speakText: String? = null,
    val speakSpeed: Int? = null,
    var baseUrl: String = "",
    private val source: BaseSource? = null,
    private val ruleData: RuleDataInterface? = null,
    private val chapter: BookChapter? = null,
    headerMapF: Map<String, String>? = null,
) : JsExtensions {
    companion object {
        val paramPattern: Pattern = Pattern.compile("\\s*,\\s*(?=\\{)")
        private val pagePattern = Pattern.compile("<(.*?)>")
        private val concurrentRecordMap = hashMapOf<String, ConcurrentRecord>()
    }

    var ruleUrl = ""
        private set
    var url: String = ""
        private set
    var body: String? = null
        private set
    var type: String? = null
        private set
    val headerMap = HashMap<String, String>()
    private var urlNoQuery: String = ""
    private var queryStr: String? = null
    private val fieldMap = LinkedHashMap<String, String>()
    private var charset: String? = null
    private var method = RequestMethod.GET
    private var proxy: String? = null
    private var retry: Int = 0
    private var useWebView: Boolean = false
    private var webJs: String? = null

    init {
        if (!mUrl.isDataUrl()) {
            val urlMatcher = paramPattern.matcher(baseUrl)
            if (urlMatcher.find()) baseUrl = baseUrl.substring(0, urlMatcher.start())
            (headerMapF ?: source?.getHeaderMap(true))?.let {
                headerMap.putAll(it)
                if (it.containsKey("proxy")) {
                    proxy = it["proxy"]
                    headerMap.remove("proxy")
                }
            }
            initUrl()
        }
    }

    /**
     * 处理url
     */
    fun initUrl() {
        ruleUrl = mUrl
        //执行@js,<js></js>
        analyzeJs()
        //替换参数
        replaceKeyPageJs()
        //处理URL
        analyzeUrl()
    }

    /**
     * 执行@js,<js></js>
     */
    private fun analyzeJs() {
        var start = 0
        var tmp: String
        val jsMatcher = JS_PATTERN.matcher(ruleUrl)
        while (jsMatcher.find()) {
            if (jsMatcher.start() > start) {
                tmp =
                    ruleUrl.substring(start, jsMatcher.start()).trim { it <= ' ' }
                if (tmp.isNotEmpty()) {
                    ruleUrl = tmp.replace("@result", ruleUrl)
                }
            }
            ruleUrl = evalJS(jsMatcher.group(2) ?: jsMatcher.group(1), ruleUrl) as String
            start = jsMatcher.end()
        }
        if (ruleUrl.length > start) {
            tmp = ruleUrl.substring(start).trim { it <= ' ' }
            if (tmp.isNotEmpty()) {
                ruleUrl = tmp.replace("@result", ruleUrl)
            }
        }
    }

    /**
     * 替换关键字,页数,JS
     */
    private fun replaceKeyPageJs() { //先替换内嵌规则再替换页数规则，避免内嵌规则中存在大于小于号时，规则被切错
        //js
        if (ruleUrl.contains("{{") && ruleUrl.contains("}}")) {
            val analyze = RuleAnalyzer(ruleUrl) //创建解析
            //替换所有内嵌{{js}}
            val url = analyze.innerRule("{{", "}}") {
                val jsEval = evalJS(it) ?: ""
                when {
                    jsEval is String -> jsEval
                    jsEval is Double && jsEval % 1.0 == 0.0 -> String.format("%.0f", jsEval)
                    else -> jsEval.toString()
                }
            }
            if (url.isNotEmpty()) ruleUrl = url
        }
        //page
        page?.let {
            val matcher = pagePattern.matcher(ruleUrl)
            while (matcher.find()) {
                val pages = matcher.group(1)!!.split(",")
                ruleUrl = if (page < pages.size) { //pages[pages.size - 1]等同于pages.last()
                    ruleUrl.replace(matcher.group(), pages[page - 1].trim { it <= ' ' })
                } else {
                    ruleUrl.replace(matcher.group(), pages.last().trim { it <= ' ' })
                }
            }
        }
    }

    /**
     * 解析Url
     */
    private fun analyzeUrl() {
        //replaceKeyPageJs已经替换掉额外内容，此处url是基础形式，可以直接切首个‘,’之前字符串。
        val urlMatcher = paramPattern.matcher(ruleUrl)
        val urlNoOption =
            if (urlMatcher.find()) ruleUrl.substring(0, urlMatcher.start()) else ruleUrl
        url = NetworkUtils.getAbsoluteURL(baseUrl, urlNoOption)
        NetworkUtils.getBaseUrl(url)?.let {
            baseUrl = it
        }
        if (urlNoOption.length != ruleUrl.length) {
            GSON.fromJsonObject<UrlOption>(ruleUrl.substring(urlMatcher.end())).getOrNull()
                ?.let { option ->
                    option.getMethod()?.let {
                        if (it.equals("POST", true)) method = RequestMethod.POST
                    }
                    option.getHeaderMap()?.forEach { entry ->
                        headerMap[entry.key.toString()] = entry.value.toString()
                    }
                    option.getBody()?.let {
                        body = it
                    }
                    type = option.getType()
                    charset = option.getCharset()
                    retry = option.getRetry()
                    useWebView = option.useWebView()
                    webJs = option.getWebJs()
                    option.getJs()?.let { jsStr ->
                        evalJS(jsStr, url)?.toString()?.let {
                            url = it
                        }
                    }
                }
        }
        headerMap[UA_NAME] ?: let {
            headerMap[UA_NAME] = AppConst.userAgent
        }
        urlNoQuery = url
        when (method) {
            RequestMethod.GET -> {
                val pos = url.indexOf('?')
                if (pos != -1) {
                    analyzeFields(url.substring(pos + 1))
                    urlNoQuery = url.substring(0, pos)
                }
            }
            RequestMethod.POST -> body?.let {
                if (!it.isJson() && !it.isXml() && headerMap["Content-Type"].isNullOrEmpty()) {
                    analyzeFields(it)
                }
            }
        }
    }

    /**
     * 解析QueryMap
     */
    private fun analyzeFields(fieldsTxt: String) {
        queryStr = fieldsTxt
        val queryS = fieldsTxt.splitNotBlank("&")
        for (query in queryS) {
            val queryM = query.splitNotBlank("=")
            val value = if (queryM.size > 1) queryM[1] else ""
            if (charset.isNullOrEmpty()) {
                if (NetworkUtils.hasUrlEncoded(value)) {
                    fieldMap[queryM[0]] = value
                } else {
                    fieldMap[queryM[0]] = URLEncoder.encode(value, "UTF-8")
                }
            } else if (charset == "escape") {
                fieldMap[queryM[0]] = EncoderUtils.escape(value)
            } else {
                fieldMap[queryM[0]] = URLEncoder.encode(value, charset)
            }
        }
    }

    /**
     * 执行JS
     */
    fun evalJS(jsStr: String, result: Any? = null): Any? {
        val bindings = SimpleBindings()
        bindings["java"] = this
        bindings["baseUrl"] = baseUrl
        bindings["cookie"] = CookieStore
        bindings["cache"] = CacheManager
        bindings["page"] = page
        bindings["key"] = key
        bindings["speakText"] = speakText
        bindings["speakSpeed"] = speakSpeed
        bindings["book"] = ruleData as? Book
        bindings["source"] = source
        bindings["result"] = result
        return SCRIPT_ENGINE.eval(jsStr, bindings)
    }

    fun put(key: String, value: String): String {
        chapter?.putVariable(key, value)
            ?: ruleData?.putVariable(key, value)
        return value
    }

    fun get(key: String): String {
        when (key) {
            "bookName" -> (ruleData as? Book)?.let {
                return it.name
            }
            "title" -> chapter?.let {
                return it.title
            }
        }
        return chapter?.getVariable(key)
            ?: ruleData?.getVariable(key)
            ?: ""
    }

    /**
     * 开始访问,并发判断
     */
    private fun fetchStart(): ConcurrentRecord? {
        source ?: return null
        val concurrentRate = source.concurrentRate
        if (concurrentRate.isNullOrEmpty()) {
            return null
        }
        val rateIndex = concurrentRate.indexOf("/")
        var fetchRecord = concurrentRecordMap[source.getKey()]
        if (fetchRecord == null) {
            fetchRecord = ConcurrentRecord(rateIndex > 0, System.currentTimeMillis(), 1)
            concurrentRecordMap[source.getKey()] = fetchRecord
            return fetchRecord
        }
        val waitTime: Int = synchronized(fetchRecord) {
            try {
                if (rateIndex == -1) {
                    if (fetchRecord.frequency > 0) {
                        return@synchronized concurrentRate.toInt()
                    }
                    val nextTime = fetchRecord.time + concurrentRate.toInt()
                    if (System.currentTimeMillis() >= nextTime) {
                        fetchRecord.time = System.currentTimeMillis()
                        fetchRecord.frequency = 1
                        return@synchronized 0
                    }
                    return@synchronized (nextTime - System.currentTimeMillis()).toInt()
                } else {
                    val sj = concurrentRate.substring(rateIndex + 1)
                    val nextTime = fetchRecord.time + sj.toInt()
                    if (System.currentTimeMillis() >= nextTime) {
                        fetchRecord.time = System.currentTimeMillis()
                        fetchRecord.frequency = 1
                        return@synchronized 0
                    }
                    val cs = concurrentRate.substring(0, rateIndex)
                    if (fetchRecord.frequency > cs.toInt()) {
                        return@synchronized (nextTime - System.currentTimeMillis()).toInt()
                    } else {
                        fetchRecord.frequency = fetchRecord.frequency + 1
                        return@synchronized 0
                    }
                }
            } catch (e: Exception) {
                return@synchronized 0
            }
        }
        if (waitTime > 0) {
            throw ConcurrentException("根据并发率还需等待${waitTime}毫秒才可以访问", waitTime = waitTime)
        }
        return fetchRecord
    }

    /**
     * 访问结束
     */
    private fun fetchEnd(concurrentRecord: ConcurrentRecord?) {
        if (concurrentRecord != null && !concurrentRecord.concurrent) {
            synchronized(concurrentRecord) {
                concurrentRecord.frequency = concurrentRecord.frequency - 1
            }
        }
    }

    /**
     * 访问网站,返回StrResponse
     */
    suspend fun getStrResponseAwait(
        jsStr: String? = null,
        sourceRegex: String? = null,
        useWebView: Boolean = true,
        debugLog: DebugLog? = null
    ): StrResponse {
        if (type != null) {
            return StrResponse(url, StringUtils.byteToHexString(getByteArrayAwait()))
        }
        val concurrentRecord = fetchStart()
        setCookie(source?.getKey())
        val strResponse: StrResponse
        if (this.useWebView && useWebView) {
            throw Exception("不支持webview")
        } else {
            strResponse = getProxyClient(proxy, debugLog).newCallStrResponse(retry) {
                addHeaders(headerMap)
                when (method) {
                    RequestMethod.POST -> {
                        url(urlNoQuery)
                        val contentType = headerMap["Content-Type"]
                        val body = body
                        if (fieldMap.isNotEmpty() || body.isNullOrBlank()) {
                            postForm(fieldMap, true)
                        } else if (!contentType.isNullOrBlank()) {
                            val requestBody = body.toRequestBody(contentType.toMediaType())
                            post(requestBody)
                        } else {
                            postJson(body)
                        }
                    }
                    else -> get(urlNoQuery, fieldMap, true)
                }
            }
        }
        fetchEnd(concurrentRecord)
        return strResponse
    }

    @JvmOverloads
    fun getStrResponse(
        jsStr: String? = null,
        sourceRegex: String? = null,
        useWebView: Boolean = true,
        debugLog: DebugLog? = null
    ): StrResponse {
        return runBlocking {
            getStrResponseAwait(jsStr, sourceRegex, useWebView, debugLog)
        }
    }

    /**
     * 访问网站,返回Response
     */
    suspend fun getResponseAwait(): Response {
        val concurrentRecord = fetchStart()
        setCookie(source?.getKey())
        @Suppress("BlockingMethodInNonBlockingContext")
        val response = getProxyClient(proxy).newCallResponse(retry) {
            addHeaders(headerMap)
            when (method) {
                RequestMethod.POST -> {
                    url(urlNoQuery)
                    val contentType = headerMap["Content-Type"]
                    val body = body
                    if (fieldMap.isNotEmpty() || body.isNullOrBlank()) {
                        postForm(fieldMap, true)
                    } else if (!contentType.isNullOrBlank()) {
                        val requestBody = body.toRequestBody(contentType.toMediaType())
                        post(requestBody)
                    } else {
                        postJson(body)
                    }
                }
                else -> get(urlNoQuery, fieldMap, true)
            }
        }
        fetchEnd(concurrentRecord)
        return response
    }

    fun getResponse(): Response {
        return runBlocking {
            getResponseAwait()
        }
    }

    /**
     * 访问网站,返回ByteArray
     */
    suspend fun getByteArrayAwait(): ByteArray {
        val concurrentRecord = fetchStart()

        @Suppress("RegExpRedundantEscape")
        val dataUriFindResult = dataUriRegex.find(urlNoQuery)
        @Suppress("BlockingMethodInNonBlockingContext")
        if (dataUriFindResult != null) {
            val dataUriBase64 = dataUriFindResult.groupValues[1]
            val byteArray = Base64.decode(dataUriBase64, Base64.DEFAULT)
            fetchEnd(concurrentRecord)
            return byteArray
        } else {
            setCookie(source?.getKey())
            val byteArray = getProxyClient(proxy).newCallResponseBody(retry) {
                addHeaders(headerMap)
                when (method) {
                    RequestMethod.POST -> {
                        url(urlNoQuery)
                        val contentType = headerMap["Content-Type"]
                        val body = body
                        if (fieldMap.isNotEmpty() || body.isNullOrBlank()) {
                            postForm(fieldMap, true)
                        } else if (!contentType.isNullOrBlank()) {
                            val requestBody = body.toRequestBody(contentType.toMediaType())
                            post(requestBody)
                        } else {
                            postJson(body)
                        }
                    }
                    else -> get(urlNoQuery, fieldMap, true)
                }
            }.bytes()
            fetchEnd(concurrentRecord)
            return byteArray
        }
    }

    fun getByteArray(): ByteArray {
        return runBlocking {
            getByteArrayAwait()
        }
    }

    /**
     * 上传文件
     */
    suspend fun upload(fileName: String, file: Any, contentType: String): StrResponse {
        return getProxyClient(proxy).newCallStrResponse(retry) {
            url(urlNoQuery)
            val bodyMap = GSON.fromJsonObject<HashMap<String, Any>>(body).getOrNull()!!
            bodyMap.forEach { entry ->
                if (entry.value.toString() == "fileRequest") {
                    bodyMap[entry.key] = mapOf(
                        Pair("fileName", fileName),
                        Pair("file", file),
                        Pair("contentType", contentType)
                    )
                }
            }
            postMultipart(type, bodyMap)
        }
    }

    /**
     *设置cookie urlOption的优先级大于书源保存的cookie
     *@param tag 书源url 缺省为传入的url
     */
    private fun setCookie(tag: String?) {
        val cookie = CookieStore.getCookie(tag ?: url)
        if (cookie.isNotEmpty()) {
            val cookieMap = CookieStore.cookieToMap(cookie)
            val customCookieMap = CookieStore.cookieToMap(headerMap["Cookie"] ?: "")
            cookieMap.putAll(customCookieMap)
            val newCookie = CookieStore.mapToCookie(cookieMap)
            newCookie?.let {
                headerMap.put("Cookie", it)
            }
        }
    }

    fun getUserAgent(): String {
        return headerMap[UA_NAME] ?: AppConst.userAgent
    }

    fun isPost(): Boolean {
        return method == RequestMethod.POST
    }

    override fun getSource(): BaseSource? {
        return source
    }

    data class UrlOption(
        private var method: String? = null,
        private var charset: String? = null,
        private var headers: Any? = null,
        private var body: Any? = null,
        private var retry: Int? = null,
        private var type: String? = null,
        private var webView: Any? = null,
        private var webJs: String? = null,
        private var js: String? = null,
    ) {
        fun setMethod(value: String?) {
            method = if (value.isNullOrBlank()) null else value
        }

        fun getMethod(): String? {
            return method
        }

        fun setCharset(value: String?) {
            charset = if (value.isNullOrBlank()) null else value
        }

        fun getCharset(): String? {
            return charset
        }

        fun setRetry(value: String?) {
            retry = if (value.isNullOrEmpty()) null else value.toIntOrNull()
        }

        fun getRetry(): Int {
            return retry ?: 0
        }

        fun setType(value: String?) {
            type = if (value.isNullOrBlank()) null else value
        }

        fun getType(): String? {
            return type
        }

        fun useWebView(): Boolean {
            return when (webView) {
                null, "", false, "false" -> false
                else -> true
            }
        }

        fun useWebView(boolean: Boolean) {
            webView = if (boolean) true else null
        }

        fun setHeaders(value: String?) {
            headers = if (value.isNullOrBlank()) {
                null
            } else {
                GSON.fromJsonObject<Map<String, Any>>(value).getOrNull()
            }
        }

        fun getHeaderMap(): Map<*, *>? {
            return when (val value = headers) {
                is Map<*, *> -> value
                is String -> GSON.fromJsonObject<Map<String, Any>>(value).getOrNull()
                else -> null
            }
        }

        fun setBody(value: String?) {
            body = when {
                value.isNullOrBlank() -> null
                value.isJsonObject() -> GSON.fromJsonObject<Map<String, Any>>(value)
                value.isJsonArray() -> GSON.fromJsonArray<Map<String, Any>>(value)
                else -> value
            }
        }

        fun getBody(): String? {
            return body?.let {
                if (it is String) it else GSON.toJson(it)
            }
        }

        fun setWebJs(value: String?) {
            webJs = if (value.isNullOrBlank()) null else value
        }

        fun getWebJs(): String? {
            return webJs
        }

        fun setJs(value: String?) {
            js = if (value.isNullOrBlank()) null else value
        }

        fun getJs(): String? {
            return js
        }
    }

    data class ConcurrentRecord(
        val concurrent: Boolean,
        var time: Long,
        var frequency: Int
    )

}
