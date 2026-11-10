package br.com.branlycrm.pipeline.dto.response;

/**
 * Origin of a lead: {@code type} (PAID_TRAFFIC, INSTAGRAM_ORGANIC, REFERRAL, GOOGLE, OTHER or
 * UNIDENTIFIED), {@code source} (AD, CODE, MANUAL or NONE) and the raw ad id, link code and title.
 */
public record LeadOriginView(String type, String source, String adId, String code, String title) {}
