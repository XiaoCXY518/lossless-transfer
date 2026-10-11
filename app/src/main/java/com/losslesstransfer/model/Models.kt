package com.losslesstransfer.model

/** BT 下载阶段，对齐 libtorrent 状态名。 */
enum class BtPhase(val label: String) {
    QUEUED("排队等待"),
    CHECKING("校验文件"),
    FETCHING_METADATA("获取元数据"),
    DOWNLOADING("下载中"),
    FINISHED("已完成"),
    SEEDING("做种中"),
    ALLOCATING("分配空间"),
    CHECKING_RESUME("校验恢复"),
    UNKNOWN("未知");

    companion object {
        /**
         * 数值码对齐 python libtorrent 的 state 顺序。
         * TODO(阶段5): 阶段5 用 libtorrent4j 实际的状态枚举替换此映射，标签名保持不变。
         */
        fun fromCode(code: Int): BtPhase = when (code) {
            0 -> QUEUED
            1 -> CHECKING
            2 -> FETCHING_METADATA
            3 -> DOWNLOADING
            4 -> FINISHED
            5 -> SEEDING
            6 -> ALLOCATING
            7 -> CHECKING_RESUME
            else -> UNKNOWN
        }
    }
}

/** BT 下载进度。 */
data class TransferProgress(
    val percent: Float = 0f,
    val speedMbps: Float = 0f,
    val peers: Int = 0,
    val phase: BtPhase = BtPhase.UNKNOWN,
    val downloadedMb: Float = 0f,
    val totalMb: Float = 0f,
)

/** HTTP 下载进度。 */
data class HttpDownloadProgress(
    val percent: Int = 0,
    val speedBytesPerSec: Long = 0L,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
)

/** 接收服务状态。 */
data class ReceiveStatus(
    val running: Boolean = false,
    val port: Int = 0,
    val lanIp: String = "",
    val saveDir: String = "",
)

/** 单个文件的发送结果。 */
data class SendFileResult(
    val relativePath: String,
    val success: Boolean,
    val message: String,
)

/** 发送统计。 */
data class SendSummary(
    val total: Int = 0,
    val successCount: Int = 0,
    val failCount: Int = 0,
)

/** 下载来源类型，用于入口分流。 */
enum class DownloadKind {
    HTTP,
    BT_MAGNET,
    BT_LOCAL_TORRENT,
    BT_REMOTE_TORRENT,
    BT_DYNAMIC,
}
