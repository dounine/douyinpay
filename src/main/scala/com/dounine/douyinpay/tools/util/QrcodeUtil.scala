package com.dounine.douyinpay.tools.util

import com.google.zxing.client.j2se.{BufferedImageLuminanceSource, MatrixToImageWriter}
import com.google.zxing.common.{BitMatrix, HybridBinarizer}
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.{BarcodeFormat, BinaryBitmap, EncodeHintType, MultiFormatReader, MultiFormatWriter, Result}

import scala.jdk.CollectionConverters._
import net.coobird.thumbnailator.Thumbnails
import net.coobird.thumbnailator.geometry.Positions

import java.io.{File, FileInputStream, InputStream}
import java.nio.file.Paths
import javax.imageio.ImageIO

object QrcodeUtil {

  private final val qrcode: QRCodeWriter = new QRCodeWriter()

  private final val charset: String = "utf-8"
  private final val config: Map[EncodeHintType, Any] = Map[EncodeHintType, Any](
    EncodeHintType.CHARACTER_SET -> "utf-8",
    EncodeHintType.MARGIN -> 0
  )

  def create(
      data: String,
      width: Int = 210,
      height: Int = 210,
      markFile: Option[File] = None
  ): File = {
    create2(
      data = data,
      width = width,
      height = height,
      markFile = markFile.map((file: File) => new FileInputStream(file))
    )
  }

  def create2(
      data: String,
      width: Int = 210,
      height: Int = 210,
      markFile: Option[InputStream] = None
  ): File = {
    val bitMatrix: BitMatrix = qrcode.encode(
      new String(data.getBytes(charset), charset),
      BarcodeFormat.QR_CODE,
      width,
      height,
      config.asJava
    )
    val tmpFile: File = File.createTempFile("qrcode", ".jpeg")
    MatrixToImageWriter.writeToPath(
      bitMatrix,
      "jpeg",
      Paths.get(tmpFile.getAbsolutePath)
    )
    markFile match {
      case Some(mf) => watermark(tmpFile, width, height, mf)
      case None     => tmpFile
    }
  }

  def decode(file: File): Option[String] = {
    try {
      val binaryBitmap: BinaryBitmap = new BinaryBitmap(
        new HybridBinarizer(
          new BufferedImageLuminanceSource(
            ImageIO.read(new FileInputStream(file))
          )
        )
      )
      val result: Result = new MultiFormatReader().decode(binaryBitmap)
      Option(result.getText)
    } catch {
      case e: Throwable => Option.empty
    }
  }

  def watermark(
      file: File,
      width: Int,
      height: Int,
      markFile: InputStream
  ): File = {
    val tmpFile = File.createTempFile("qrcode", ".jpeg")
    Thumbnails
      .of(file)
      .size(width, height)
      .watermark(
        Positions.CENTER,
        ImageIO.read(markFile),
        1.0f
      )
      .outputQuality(1.0f)
      .toFile(tmpFile)
    tmpFile
  }

}
