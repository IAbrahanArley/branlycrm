package br.com.branlycrm.whatsapp.domain;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Accepted media types (CRM-084b1, ADR-036), detected from the first bytes of the file, never from
 * the type Meta or the sender declared. Each one has the size limit WhatsApp itself applies.
 */
public enum MediaType {
  JPEG("image/jpeg", "jpg", Kind.IMAGE),
  PNG("image/png", "png", Kind.IMAGE),
  WEBP("image/webp", "webp", Kind.IMAGE),
  MP4("video/mp4", "mp4", Kind.VIDEO),
  THREE_GP("video/3gpp", "3gp", Kind.VIDEO),
  M4A("audio/mp4", "m4a", Kind.AUDIO),
  OGG("audio/ogg", "ogg", Kind.AUDIO),
  MP3("audio/mpeg", "mp3", Kind.AUDIO),
  AAC("audio/aac", "aac", Kind.AUDIO),
  AMR("audio/amr", "amr", Kind.AUDIO),
  PDF("application/pdf", "pdf", Kind.DOCUMENT),
  DOCX(
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      "docx",
      Kind.DOCUMENT),
  XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx", Kind.DOCUMENT),
  PPTX(
      "application/vnd.openxmlformats-officedocument.presentationml.presentation",
      "pptx",
      Kind.DOCUMENT);

  public enum Kind {
    IMAGE(5L * 1024 * 1024),
    VIDEO(16L * 1024 * 1024),
    AUDIO(16L * 1024 * 1024),
    DOCUMENT(100L * 1024 * 1024);

    private final long maxBytes;

    Kind(long maxBytes) {
      this.maxBytes = maxBytes;
    }

    public long maxBytes() {
      return maxBytes;
    }
  }

  /** The largest file accepted at all: the download stops past it. */
  public static final long MAX_BYTES = Kind.DOCUMENT.maxBytes();

  /** How many first bytes {@link #detect} needs (an Office file needs its whole head). */
  public static final int HEAD_BYTES = 64 * 1024;

  private final String contentType;
  private final String extension;
  private final Kind kind;

  MediaType(String contentType, String extension, Kind kind) {
    this.contentType = contentType;
    this.extension = extension;
    this.kind = kind;
  }

  public String contentType() {
    return contentType;
  }

  public String extension() {
    return extension;
  }

  public Kind kind() {
    return kind;
  }

  /** The largest file the clinic sends from the CRM (CRM-084b2), whatever the type allows. */
  public static final long OUTBOUND_MAX_BYTES = 16L * 1024 * 1024;

  /**
   * The WhatsApp message type that sends this file (CRM-084b2): {@code image}, {@code video},
   * {@code audio} or {@code document}; empty for a type WhatsApp does not send as itself (WebP is
   * only a sticker).
   */
  public Optional<String> outboundMessageType() {
    return switch (this) {
      case WEBP -> Optional.empty();
      default -> Optional.of(kind.name().toLowerCase(java.util.Locale.ROOT));
    };
  }

  /** The size limit of a sent file of this type. */
  public long outboundMaxBytes() {
    return Math.min(kind.maxBytes(), OUTBOUND_MAX_BYTES);
  }

  /** The type of a file from its first bytes, or empty for anything not accepted. */
  public static Optional<MediaType> detect(byte[] head) {
    if (head == null || head.length < 4) return Optional.empty();
    if (starts(head, 0xFF, 0xD8, 0xFF)) return Optional.of(JPEG);
    if (starts(head, 0x89, 'P', 'N', 'G')) return Optional.of(PNG);
    if (ascii(head, 0, "RIFF") && ascii(head, 8, "WEBP")) return Optional.of(WEBP);
    if (ascii(head, 4, "ftyp") && head.length >= 12) {
      String brand = new String(head, 8, 4, StandardCharsets.US_ASCII);
      // HEIC, AVIF and QuickTime share the box format but are not accepted types.
      if (brand.startsWith("hei")
          || brand.startsWith("hev")
          || brand.startsWith("mif")
          || brand.startsWith("msf")
          || brand.startsWith("avi")
          || brand.startsWith("qt")) {
        return Optional.empty();
      }
      if (brand.startsWith("3gp")) return Optional.of(THREE_GP);
      if (brand.equals("M4A ")) return Optional.of(M4A);
      return Optional.of(MP4);
    }
    if (ascii(head, 0, "OggS")) return Optional.of(OGG);
    if (ascii(head, 0, "#!AMR")) return Optional.of(AMR);
    if (ascii(head, 0, "ID3")) return Optional.of(MP3);
    if ((head[0] & 0xFF) == 0xFF && ((head[1] & 0xF6) == 0xF0)) return Optional.of(AAC);
    if ((head[0] & 0xFF) == 0xFF && ((head[1] & 0xE0) == 0xE0)) return Optional.of(MP3);
    if (ascii(head, 0, "%PDF-")) return Optional.of(PDF);
    if (starts(head, 'P', 'K', 0x03, 0x04)) return office(head);
    return Optional.empty();
  }

  /** An Office Open XML file, told apart by its main part; any other ZIP is refused. */
  private static Optional<MediaType> office(byte[] head) {
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(head))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        String name = entry.getName();
        if (name.startsWith("word/")) return Optional.of(DOCX);
        if (name.startsWith("xl/")) return Optional.of(XLSX);
        if (name.startsWith("ppt/")) return Optional.of(PPTX);
      }
    } catch (IOException | RuntimeException truncated) {
      // The head ended in the middle of an entry: decided by what was read so far.
    }
    return Optional.empty();
  }

  private static boolean starts(byte[] head, int... bytes) {
    if (head.length < bytes.length) return false;
    for (int i = 0; i < bytes.length; i++) {
      if ((head[i] & 0xFF) != bytes[i]) return false;
    }
    return true;
  }

  private static boolean ascii(byte[] head, int offset, String text) {
    if (head.length < offset + text.length()) return false;
    for (int i = 0; i < text.length(); i++) {
      if (head[offset + i] != text.charAt(i)) return false;
    }
    return true;
  }
}
