package srdc.stage.shl

import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.{BarcodeFormat, EncodeHintType}

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * QR codes of SHL URIs: a PNG data URL, 400 x 400 px, error correction M, a quiet zone of one module and the SMART logo
 * on a white background in the centre (the logo and its border cover about 5% of the image, as recommended)
 */
object ShlQrCode {
  System.setProperty("java.awt.headless", "true")

  private val Size = 400
  private val LogoBorder = 5
  private lazy val logo: BufferedImage = {
    val in = getClass.getResourceAsStream("/shl/smart-logo-qr.png")
    try ImageIO.read(in) finally in.close()
  }

  def dataUrl(text: String): String = {
    val hints = new java.util.EnumMap[EncodeHintType, AnyRef](classOf[EncodeHintType])
    hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
    hints.put(EncodeHintType.MARGIN, Integer.valueOf(1))
    hints.put(EncodeHintType.CHARACTER_SET, "UTF-8")
    val qr = MatrixToImageWriter.toBufferedImage(new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, Size, Size, hints))
    val image = new BufferedImage(qr.getWidth, qr.getHeight, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    try {
      graphics.drawImage(qr, 0, 0, null)
      val x0 = Math.round((image.getWidth - logo.getWidth) / 2.0).toInt
      val y0 = Math.round((image.getHeight - logo.getHeight) / 2.0).toInt
      graphics.setColor(Color.WHITE)
      graphics.fillRect(x0 - LogoBorder, y0 - LogoBorder, logo.getWidth + 2 * LogoBorder, logo.getHeight + 2 * LogoBorder)
      graphics.drawImage(logo, x0, y0, null) // alpha blended on the white background
    } finally graphics.dispose()
    val png = new ByteArrayOutputStream()
    ImageIO.write(image, "png", png)
    "data:image/png;base64," + Base64.getEncoder.encodeToString(png.toByteArray)
  }
}
