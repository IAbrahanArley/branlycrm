package br.com.branlycrm.whatsapp.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/** Media types by their first bytes (CRM-084b1), never by the declared type. */
class MediaTypeTest {
  @Test
  void acceptedTypesAreDetectedFromTheirFirstBytes() throws IOException {
    assertThat(MediaType.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0))).contains(MediaType.JPEG);
    assertThat(MediaType.detect(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A))).contains(MediaType.PNG);
    assertThat(MediaType.detect(ascii("RIFF\0\0\0\0WEBPVP8 "))).contains(MediaType.WEBP);
    assertThat(MediaType.detect(ascii("\0\0\0\u0018ftypisom"))).contains(MediaType.MP4);
    assertThat(MediaType.detect(ascii("\0\0\0\u0018ftyp3gp4"))).contains(MediaType.THREE_GP);
    assertThat(MediaType.detect(ascii("\0\0\0\u0018ftypM4A "))).contains(MediaType.M4A);
    assertThat(MediaType.detect(ascii("OggS\0\2"))).contains(MediaType.OGG);
    assertThat(MediaType.detect(ascii("ID3\3\0"))).contains(MediaType.MP3);
    assertThat(MediaType.detect(bytes(0xFF, 0xFB, 0x90, 0x00))).contains(MediaType.MP3);
    assertThat(MediaType.detect(bytes(0xFF, 0xF1, 0x50, 0x80))).contains(MediaType.AAC);
    assertThat(MediaType.detect(ascii("#!AMR\n"))).contains(MediaType.AMR);
    assertThat(MediaType.detect(ascii("%PDF-1.7\n"))).contains(MediaType.PDF);
    assertThat(MediaType.detect(zip("word/document.xml"))).contains(MediaType.DOCX);
    assertThat(MediaType.detect(zip("xl/workbook.xml"))).contains(MediaType.XLSX);
    assertThat(MediaType.detect(zip("ppt/presentation.xml"))).contains(MediaType.PPTX);
  }

  @Test
  void anythingElseIsRefused() throws IOException {
    assertThat(MediaType.detect(ascii("MZ\u0090\0executable"))).isEmpty();
    assertThat(MediaType.detect(ascii("<html><script>"))).isEmpty();
    assertThat(MediaType.detect(zip("payload.js"))).isEmpty();
    assertThat(MediaType.detect(ascii("GIF8"))).isEmpty();
    assertThat(MediaType.detect(ascii("\u0000\u0000\u0000\u0018ftypheic"))).isEmpty();
    assertThat(MediaType.detect(ascii("\u0000\u0000\u0000\u0018ftypavif"))).isEmpty();
    assertThat(MediaType.detect(ascii("\u0000\u0000\u0000\u0014ftypqt  "))).isEmpty();
    assertThat(MediaType.detect(new byte[] {1, 2})).isEmpty();
    assertThat(MediaType.detect(null)).isEmpty();
    assertThat(MediaType.JPEG.kind().maxBytes()).isEqualTo(5L * 1024 * 1024);
    assertThat(MediaType.PDF.kind().maxBytes()).isEqualTo(MediaType.MAX_BYTES);
  }

  private static byte[] bytes(int... values) {
    byte[] bytes = new byte[values.length];
    for (int i = 0; i < values.length; i++) bytes[i] = (byte) values[i];
    return bytes;
  }

  private static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.ISO_8859_1);
  }

  private static byte[] zip(String entry) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
      zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry(entry));
      zip.write("<x/>".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return out.toByteArray();
  }
}
