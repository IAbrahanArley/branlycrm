package br.com.branlycrm.whatsapp.secrets;

/** What a sealed value is; part of its authenticated context, so values cannot be swapped. */
public enum ChannelSecretPurpose {
  ACCESS_TOKEN,
  REGISTRATION_PIN;

  String context(java.util.UUID channelId) {
    if (channelId == null) throw new IllegalArgumentException("No channel");
    return "branly-whatsapp:" + name() + ":" + channelId;
  }
}
