package cn.leo.udp.net

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.util.Log
import cn.leo.udp.manager.WifiLManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Created by Leo on 2018/2/26.
 * 安卓UDP底层传输模块，拆分数据包传输
 * 无丢包处理。
 */

class UdpFrame(private var mOnDataArrivedListener: OnDataArrivedListener,
               private val mListenPort: Int = 37320) : Thread() {
    private val hostFlag = "host"
    private val dataFlag = "data"
    //拆分单个包大小(包的个数为byte最大值)这个值不能超过UDP包最大值64K。
    private val mPackSize = 1024
    private val mSendSocket = DatagramSocket()
    private val mReceiveSocket = DatagramSocket(this.mListenPort)
    private var mHandlerThread: HandlerThread = HandlerThread("sendThread")
    private var mSendHandler: Handler

    interface OnDataArrivedListener {
        fun onDataArrived(data: ByteArray, length: Int, host: String)
    }

    override fun run() {
        listen()
    }

    init {
        //开启发送数据子线程
        mHandlerThread.start()
        //发送数据子线程handler
        mSendHandler = Handler(mHandlerThread.looper) {
            if (it.what == -1) {
                safetyClose()
                return@Handler true
            }
            val data = it.data
            val host = data.getString(hostFlag)
            val byteArray = data.getByteArray(dataFlag)
            sendData(byteArray, host)
            true
        }
        start() //启动监听
    }

    /**
     *发送
     */
    fun send(data: ByteArray, host: String) {
        val message = Message.obtain()
        val bundle = Bundle()
        bundle.putString(hostFlag, host)
        bundle.putByteArray(dataFlag, data)
        message.data = bundle
        mSendHandler.sendMessage(message)
    }

    /**
     * 发送局域网广播
     */
    fun sendBroadcast(context: Context, data: ByteArray) {
        val broadCastAddress = WifiLManager.getBroadcastAddress(context)
        send(data, broadCastAddress)
    }

    /**
     * 监听UDP信息,接受数据
     */
    private fun listen() {
        val data = ByteArray(mPackSize)
        val dp = DatagramPacket(data, data.size)
        //缓存数据
        val cache = ArrayList<ByteArray>()
        while (true) {
            try {
                mReceiveSocket.receive(dp)
                //检查数据包头部
                val head = ByteArray(2)
                val body = ByteArray(dp.length - 2)
                //取出头部
                System.arraycopy(data, 0, head, 0, head.size)
                //取出数据体
                System.arraycopy(data, 2, body, 0, body.size)
                //安全退出，不再监听
                if (head[0] == (-0xEE).toByte() && head[1] == (-0xDD).toByte()) {
                    val remoteAddress = dp.address.hostAddress
                    if ("127.0.0.1" == remoteAddress) {
                        break
                    }
                }
                //不符合规范的数据包直接抛弃
                if (head[0] < head[1]) {
                    continue
                }
                //数据只有1个包
                if (head[0] == 1.toByte()) {
                    //数据回调给上层协议层
                    mOnDataArrivedListener.onDataArrived(body, body.size,
                            dp.address.hostAddress)
                } else {
                    //新的数据包组到来清空缓存
                    if (head[1] == 1.toByte()) {
                        cache.clear()
                    }
                    //缓存数据包(漏数据包则不缓存)
                    if (cache.size + 1 == head[1].toInt()) {
                        cache.add(body)
                    }
                    //所有数据包都抵达完成则拼接
                    if (head[0] == head[1]) {
                        //数据包完整的话
                        if (cache.size == head[0].toInt()) {
                            //开始组装数据
                            //获取数据总长度
                            val dataLength = cache.sumBy { it.size }
                            val sumData = ByteArray(dataLength)
                            //已经拼接长度
                            var length = 0
                            for (bytes in cache) {
                                System.arraycopy(bytes, 0, sumData, length, bytes.size)
                                length += bytes.size
                            }
                            //数据回调给上层协议层
                            mOnDataArrivedListener.onDataArrived(sumData, sumData.size,
                                    dp.address.hostAddress)
                        } else {
                            //数据包不完整
                            Log.e("udp", " -- data is incomplete")
                        }
                    }
                }
            } catch (e: Exception) {

            }
        }
        mReceiveSocket.disconnect()
        mReceiveSocket.close()
        mSendSocket.close()
        mHandlerThread.quit()
    }

    /**
     *发送数据包
     * 最大127K
     */
    private fun sendData(data: ByteArray, host: String) {
        //发送地址
        val ia = InetSocketAddress(host, this.mListenPort)
        //已发送字节数
        var sendLength = 0
        //要发送的长度
        val dataSize = data.size
        //拆分后包的总个数
        val packCount = dataSize / (mPackSize - 2 + 1) + 1
        //循环发送数据包
        while (sendLength < dataSize) {
            //要发送的数据(长度不超过最小包长)
            val length = if (dataSize - sendLength > mPackSize - 2) {
                mPackSize - 2
            } else {
                (dataSize - sendLength)
            } + 2
            //定义新包大小
            val pack = ByteArray(length)
            //-2 表示去掉头长度，+1表示，长度刚好1个包的时候不会多出来
            //当前包序号，从1开始
            val packIndex = sendLength / (mPackSize - 2) + 1
            val head = byteArrayOf(packCount.toByte(), packIndex.toByte())
            //添加数据头
            System.arraycopy(head, 0, pack, 0, head.size)
            //添加数据体
            System.arraycopy(data, sendLength, pack, head.size, pack.size - head.size)
            //发送小包
            val dp = DatagramPacket(pack, pack.size, ia)
            try {
                mSendSocket.send(dp)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            //已发长度累计
            sendLength += pack.size - 2
        }
    }

    /**
     * 安全关闭udp并释放端口
     */
    fun close() {
        mSendHandler.obtainMessage(-1).sendToTarget()
    }

    private fun safetyClose() {
        val ia = InetSocketAddress("localhost", mListenPort)
        val head = byteArrayOf((-0xEE).toByte(), (-0xDD).toByte())
        val dp = DatagramPacket(head, head.size, ia)
        mSendSocket.send(dp)
        //停止发送线程
        mHandlerThread.quit()
    }
}