package com.paddle.ocr.demo

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi

/** Indexed decoding: every video frame, not periodic/keyframe sampling. Use off the UI thread. */
@RequiresApi(Build.VERSION_CODES.P)
class VideoFrameReader(context: Context, uri: Uri) : AutoCloseable {
    private val retriever = MediaMetadataRetriever()
    val frameCount: Int

    init {
        try {
            retriever.setDataSource(context, uri)
            frameCount = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                ?.toIntOrNull()?.takeIf { it > 0 }
                ?: error("无法获得视频总帧数，此视频不支持逐帧读取")
        } catch (t: Throwable) {
            retriever.release()
            throw t
        }
    }

    fun read(index: Int): Bitmap {
        require(index in 0 until frameCount)
        val params = MediaMetadataRetriever.BitmapParams().apply {
            preferredConfig = Bitmap.Config.ARGB_8888
        }
        return retriever.getFrameAtIndex(index, params)
            ?: error("无法解码第 ${index + 1} 帧，已停止（不会跳过失败帧）")
    }

    override fun close() = retriever.release()
}
