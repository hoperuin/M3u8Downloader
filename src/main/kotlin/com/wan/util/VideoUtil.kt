package com.wan.util

import org.apache.commons.io.FileUtils
import java.io.File
import java.net.*
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread


/**
 *
 * @author StarsOne
 * @date Create in  2019/9/12 0012 14:42
 * @description
 *
 */
class VideoUtil {
    var progess = 0
    var downloadedFlag = false

    private var dirFile: File? = null
    var tsUrls = arrayListOf<String>()
    private val tsFiles = arrayListOf<File>() //所有ts文件列表
    private val outTsFiles = arrayListOf<File>() //已解密的ts文件列表
    private val tsNames = arrayListOf<String>() //m3u8文件中的正确顺序的ts文件名

    private var isEncrypt = false    //当前m3u8文件是否使用加密
    private var webUrl = "" //当前m3u8文件url的前缀网址

    private val algorithm = "AES"
    private val transformation = "AES/CBC/PKCS5Padding"
    private val cipher = Cipher.getInstance(transformation) //解密对象

    private var keyBytes = byteArrayOf()
    private var ivBytes = byteArrayOf()

    /**
     * @param m3u8Url m3u8网址
     * @param dirPath 下载的目录（之后下载的ts文件以及合并的输出的mp4文件都在此目录下）
     *
     */
    constructor(m3u8Url: String, extParam: Map<String, String>?, dirPath: String) {

        //输入错误检测（判断m3u8Url是网址）
        val urlRegex = "[a-zA-z]+://[^\\s]*"

        dirFile = File(dirPath)
        //文件夹可能不存在
        if (!dirFile!!.exists()) {
            dirFile!!.mkdirs()
        }
        val m3u8File = File(dirFile, "index.m3u8")
        if (Pattern.matches(urlRegex, m3u8Url)) {
            if (m3u8Url.endsWith(".m3u8")) {
                webUrl = m3u8Url.substringBeforeLast("/")
                //从url下载m3u8文件
                downloadM3u8File(m3u8Url, dirFile!!)
                //解析m3u8文件
                getMessageFromM3u8File(m3u8File, extParam)
                // 初始化解密工具对象
                val skey = SecretKeySpec(keyBytes, algorithm)
                val iv = IvParameterSpec(ivBytes)
                cipher.init(Cipher.DECRYPT_MODE, skey, iv)
            } else {
                println("该网址不是一个m3u8文件")
            }

        } else {
            println("参数不是一个url网址")
        }
    }

    /**
     * 下载ts文件
     * @param threadCount 线程数（默认开启5个线程下载，速度较快，100M宽带测试速度有17M/s）
     */
    fun downloadTsFile(threadCount: Int = 5) {
        val step = tsUrls.size / threadCount
        val yu = tsUrls.size % threadCount
        thread {
            val firstList = tsUrls.take(step)
            downloadTsList(firstList)
        }
        thread {
            val lastList = tsUrls.takeLast(step + yu)
            downloadTsList(lastList)
        }

        for (i in 1..threadCount - 2) {
            val list = tsUrls.subList(i * step, (i + 1) * step + 1)
            thread {
                downloadTsList(list)
            }
        }
        /*val countDownLatch = CountDownLatch(threadCount)
        val step = tsUrls.size / threadCount
        val yu = tsUrls.size % threadCount
        thread {
            val firstList = tsUrls.take(step)
            downloadTsList(firstList)
            countDownLatch.countDown()
        }
        thread {
            val lastList = tsUrls.takeLast(step + yu)
            downloadTsList(lastList)
            countDownLatch.countDown()
        }

        for (i in 1..threadCount - 2) {
            val list = tsUrls.subList(i * step, (i + 1) * step + 1)
            thread {
                downloadTsList(list)
                countDownLatch.countDown()
            }
        }
        countDownLatch.await()
        println("所有ts文件下载完毕")*/
    }

    private fun downloadTsList(tsUrls: List<String>) {
        for (tsUrl in tsUrls) {
            var fName = tsUrl.substringAfterLast("/")
            fName = getFileName(fName)
            val tsFile = File(dirFile, fName)
            if (!tsFile.exists()) {
                downloadFile(tsUrl, File(dirFile, fName), null)
            }
            tsFiles.add(tsFile)
            progess++
            if (progess == tsUrls.size) {
                downloadedFlag = true
            }
        }
    }

    /**
     * 解密所有的ts文件
     */
    fun decryptTs() {
        if (isEncrypt) {
            for (tsName in tsNames) {
                val tsFile = File(dirFile, tsName)
                try {
                    if (tsFile.exists()) {
                        val outBytes = cipher.doFinal(tsFile.readBytes())
                        val outTsFile = File(dirFile, "out_$tsName")
                        outTsFile.writeBytes(outBytes)
                        outTsFiles.add(outTsFile)
                    }
                } catch (e: Exception) {
                    println("${tsFile.name}解密出错，错误为${e.message}")
                }
            }
            println("已解密所有ts文件")
        } else {
            println("ts文件未加密")
        }

    }

    /**
     * 合并ts文件
     * @param fileName 输出文件名（默认为out.mp4)，扩展名可不输
     * @return 输出mp4文件File对象
     */
    fun mergeTsFile(fileName: String = "out.mp4"): File {

        val outFile = if (!fileName.endsWith(".mp4")) File(dirFile, "$fileName.mp4") else File(dirFile, fileName)
        //如果加密了，对解密出来的ts文件合并
        if (isEncrypt) {
            for (tsName in tsNames) {
                for (outTsFile in outTsFiles) {
                    //某些ts文件可能解密失败，所以得判断文件是否存在
                    if (outTsFile.name.contains(tsName) && outTsFile.exists()) {
                        FileUtils.writeByteArrayToFile(outFile, outTsFile.readBytes(), true)
                        //追加之后删除文件
                        outTsFile.delete()
                        break
                    }
                }
            }

        } else {
            //直接对已下载的ts文件进行合并
            for (tsName in tsNames) {
                for (tsFile in tsFiles) {
                    if (tsFile.name.contains(tsName) && tsFile.exists()) {
                        FileUtils.writeByteArrayToFile(outFile, tsFile.readBytes(), true)
                        break
                    }
                }
            }
        }

        //删除ts文件
        for (tsFile in tsFiles) {
            if (tsFile.exists()) {
                tsFile.delete()
            }
        }
        return outFile
    }

    /**
     * 从m3u8文件中获取ts文件的地址、key的信息以及IV
     */
    private fun getMessageFromM3u8File(m3u8File: File, extParam: Map<String, String>?) {
        val urlRegex = "[a-zA-z]+://[^\\s]*"//网址正则表达式

        //读取m3u8（注意是utf-8格式）
        val readLines = m3u8File.readLines(charset("utf-8"))
        for (line in readLines) {
            //是否为AES128加密
            if (line.contains("AES-128")) {
                //获得key的url
                val start = line.indexOf("\"")
                val last = line.lastIndexOf("\"")
                val keyUrl = line.substring(start + 1, last)

                //keyUrl可能是网址
                keyBytes = if (Pattern.matches(urlRegex, keyUrl)) {
                    downloadKeyFile(keyUrl, m3u8File.parentFile, extParam)
                } else {
                    //不是网址，则进行拼接
                    // 拼接key文件的url文件，并下载在本地，获得key文件的字节数组
                    downloadKeyFile("$webUrl/$keyUrl", m3u8File.parentFile, extParam)
                }
                //获得偏移量IV字符串
                val ivString = if (line.contains("IV=0x")) line.substringAfter("IV=0x") else ""
                //m3u8未定义IV则使用默认的字节数组（0）
                ivBytes = if (ivString.isBlank()) byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0) else decodeHex(ivString)
                isEncrypt = true
            }
            if (line.contains(".ts")) {
                //ts是否是链接形式
                if (Pattern.matches(urlRegex, line)) {
                    val tsName = line.substringAfterLast("/")
                    tsNames.add(tsName)
                    tsUrls.add(line)
                } else {
                    //按顺序添加ts文件名，之后合并需要
                    var fName = line
                    fName = getFileName(fName)
                    tsNames.add(fName)
                    //拼接ts文件的url地址，添加到列表中
                    tsUrls.add("$webUrl/$line")
                }
            }
        }
    }

    private fun getFileName(fName: String): String {
        var fName1 = fName
        if (!fName1.endsWith(".ts")) {
            if (fName1.contains("start=")
                    && fName1.contains("end=")) {
                var si = fName1.indexOf("start=") + 6
                var se = fName1.indexOf("&")
                fName1 = fName1.substring(si, se)
            }
        }
        return fName1
    }


    /**
     * 下载m3u8文件到本地
     * @param m3u8Url m3u8网址
     * @param dirFile 文件夹目录
     */
    private fun downloadM3u8File(m3u8Url: String, dirFile: File) {
        downloadFile(m3u8Url, File(dirFile, "index.m3u8"), null)
    }

    /**
     * 下载key文件到本地
     * @param keyUrl key文件网址
     * @param dirFile 文件夹目录
     * @return key文件的字节数组（之后解密需要）
     */
    private fun downloadKeyFile(keyUrl: String, dirFile: File, extParam: Map<String, String>?): ByteArray {
        val keyFile = File(dirFile, "key.key")
        downloadFile(keyUrl, keyFile, extParam)
        return keyFile.readBytes()
    }


    /**
     * 下载文件到本地
     * @param url 网址
     * @param file 文件
     */
    private fun downloadFile(url: String, file: File, extParam: Map<String, String>?) {
        val u = URL(url)
        var conn = u.openConnection() as HttpURLConnection

        val cookieManager = CookieManager()
        CookieHandler.setDefault(cookieManager)

        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.106 Mobile Safari/537.36")
        extParam?.forEach { (k, v) ->
            var vv = v
            if (k == "cookie" || k == "Cookie") {
                val cookies = HttpCookie.parse(v)
                cookies.forEach { cookie -> cookieManager.cookieStore.add(null, cookie) }
                conn.setRequestProperty("Cookie",
                        cookieManager.cookieStore.cookies.joinToString(";"));
            } else {
                conn.setRequestProperty(k, vv)
            }
        }
        conn.requestMethod = "GET"

        val bytes = conn.inputStream.buffered(512).readBytes()
        file.writeBytes(bytes)
    }

    /**
     * 将字符串转为16进制并返回字节数组
     */
    private fun decodeHex(input: String): ByteArray {
        val data = input.toCharArray()
        val len = data.size
        if (len and 0x01 != 0) {
            try {
                throw Exception("Odd number of characters.")
            } catch (e: Exception) {
                e.printStackTrace()
            }

        }
        val out = ByteArray(len shr 1)

        try {
            var i = 0
            var j = 0
            while (j < len) {
                var f = toDigit(data[j], j) shl 4
                j++
                f = f or toDigit(data[j], j)
                j++
                out[i] = (f and 0xFF).toByte()
                i++
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return out
    }

    @Throws(Exception::class)
    private fun toDigit(ch: Char, index: Int): Int {
        val digit = Character.digit(ch, 16)
        if (digit == -1) {
            throw Exception("Illegal hexadecimal character $ch at index $index")
        }
        return digit
    }


}