package br.com.branlycrm.catalog.dto.response;

import java.util.UUID;

public record ProfessionalView(UUID id, String name, boolean active, long version) {}
