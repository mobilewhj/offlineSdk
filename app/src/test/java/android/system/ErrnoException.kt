package android.system

/** 仅 JVM 测试使用的 errno 边界；不是 Android 系统行为或 API24 设备证据。 */
class ErrnoException @JvmOverloads constructor(
    @JvmField val functionName: String,
    @JvmField val errno: Int,
    cause: Throwable? = null,
) : Exception("$functionName: errno=$errno", cause)
