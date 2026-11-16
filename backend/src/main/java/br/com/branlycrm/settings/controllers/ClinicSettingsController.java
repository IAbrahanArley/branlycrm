package br.com.branlycrm.settings.controllers;

import br.com.branlycrm.identity.security.CrmPrincipal;
import br.com.branlycrm.identity.security.SelectedClinic;
import br.com.branlycrm.settings.dto.request.ClinicNameRequest;
import br.com.branlycrm.settings.dto.request.TimeZoneRequest;
import br.com.branlycrm.settings.dto.response.ClinicSettingsView;
import br.com.branlycrm.settings.services.ClinicSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Name and time zone of the clinic selected in the session (CRM-013). */
@Tag(name = "Clinic settings", description = "Name and time zone of the selected clinic")
@SecurityRequirement(name = "session")
@RestController
@RequestMapping("/v1/clinic/settings")
public final class ClinicSettingsController {
  private final ClinicSettingsService settings;

  public ClinicSettingsController(ClinicSettingsService settings) {
    this.settings = settings;
  }

  @Operation(operationId = "getClinicSettings", summary = "Clinic name and time zone (all roles)")
  @ApiResponse(responseCode = "200", description = "Settings")
  @GetMapping
  public ClinicSettingsView get(
      @AuthenticationPrincipal CrmPrincipal principal, HttpSession session) {
    return settings.get(SelectedClinic.require(session), principal.subjectId());
  }

  @Operation(operationId = "renameClinic", summary = "Rename the clinic (OWNER)")
  @ApiResponse(responseCode = "200", description = "Settings after the change")
  @ApiResponse(responseCode = "400", description = "INVALID_INPUT: clinicName or body")
  @SecurityRequirement(name = "csrf")
  @PutMapping("/name")
  public ClinicSettingsView rename(
      @RequestBody ClinicNameRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return settings.rename(
        SelectedClinic.require(session), principal.subjectId(), request.clinicName());
  }

  @Operation(
      operationId = "changeClinicTimeZone",
      summary = "Change the clinic time zone (OWNER); applies to dates computed from now on")
  @ApiResponse(responseCode = "200", description = "Settings after the change")
  @ApiResponse(responseCode = "400", description = "INVALID_INPUT: timeZone or body")
  @SecurityRequirement(name = "csrf")
  @PutMapping("/time-zone")
  public ClinicSettingsView changeTimeZone(
      @RequestBody TimeZoneRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return settings.changeTimeZone(
        SelectedClinic.require(session), principal.subjectId(), request.timeZone());
  }
}
