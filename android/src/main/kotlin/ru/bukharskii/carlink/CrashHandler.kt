import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.util.*
import kotlin.system.exitProcess


fun interface CrashCallback {
    fun onCrash(crashDetails: String)
}
class CrashHandler(callback: CrashCallback) : Thread.UncaughtExceptionHandler {

    private val callback = callback

    override fun uncaughtException(thread: Thread, exception: Throwable) {

        val errorMessage = StringBuilder()

        val stackTrace = StringWriter()
        exception.printStackTrace(PrintWriter(stackTrace))

        errorMessage.append(thread.toString())
        errorMessage.append("\n")
        errorMessage.append(exception.toString())
        errorMessage.append("\n")
        errorMessage.append(stackTrace.toString())

        callback.onCrash(errorMessage.toString())
    }
}