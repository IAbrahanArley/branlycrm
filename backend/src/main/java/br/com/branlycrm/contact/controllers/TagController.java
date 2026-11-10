package br.com.branlycrm.contact.controllers;

import br.com.branlycrm.contact.dto.request.TagRequest;
import br.com.branlycrm.contact.dto.response.TagView;
import br.com.branlycrm.contact.services.TagService;
import br.com.branlycrm.identity.security.CrmPrincipal;
import br.com.branlycrm.identity.security.SelectedClinic;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Tag catalog of the clinic selected in the session (PAC-06). The catalog is small and returned
 * whole, without pagination.
 */
@Tag(name = "Tags", description = "Manual tags of the selected clinic")
@SecurityRequirement(name = "session")
@RestController
@RequestMapping("/v1/clinic/tags")
public final class TagController {
  private final TagService tags;

  public TagController(TagService tags) {
    this.tags = tags;
  }

  @Operation(
      operationId = "listTags",
      summary = "List the clinic's tags (all roles), ordered by name")
  @ApiResponse(responseCode = "200", description = "Every tag of the clinic")
  @ApiResponse(responseCode = "403", description = "CLINIC_ACCESS_DENIED")
  @ApiResponse(responseCode = "409", description = "CLINIC_NOT_SELECTED")
  @GetMapping
  public List<TagView> list(@AuthenticationPrincipal CrmPrincipal principal, HttpSession session) {
    return tags.list(SelectedClinic.require(session), principal.subjectId());
  }

  @Operation(operationId = "createTag", summary = "Create a tag (OWNER, MANAGER, ATTENDANT)")
  @ApiResponse(responseCode = "201", description = "Tag created; Location points to it")
  @ApiResponse(responseCode = "400", description = "INVALID_INPUT: name or body")
  @ApiResponse(responseCode = "403", description = "CLINIC_ACCESS_DENIED")
  @ApiResponse(
      responseCode = "409",
      description =
          "TAG_DUPLICATE (same name, whatever the case), TAG_LIMIT (500 tags) or"
              + " CLINIC_NOT_SELECTED")
  @SecurityRequirement(name = "csrf")
  @PostMapping
  public ResponseEntity<TagView> create(
      @RequestBody TagRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    TagView created = tags.create(SelectedClinic.require(session), principal.subjectId(), request);
    return ResponseEntity.created(
            ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{tagId}")
                .buildAndExpand(created.id())
                .toUri())
        .body(created);
  }

  @Operation(operationId = "renameTag", summary = "Rename a tag (OWNER, MANAGER)")
  @ApiResponse(responseCode = "200", description = "Renamed tag")
  @ApiResponse(responseCode = "400", description = "INVALID_INPUT: tagId, name or body")
  @ApiResponse(responseCode = "403", description = "CLINIC_ACCESS_DENIED")
  @ApiResponse(responseCode = "404", description = "TAG_NOT_FOUND")
  @ApiResponse(responseCode = "409", description = "TAG_DUPLICATE or CLINIC_NOT_SELECTED")
  @SecurityRequirement(name = "csrf")
  @PutMapping("/{tagId}")
  public TagView rename(
      @PathVariable UUID tagId,
      @RequestBody TagRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return tags.rename(SelectedClinic.require(session), principal.subjectId(), tagId, request);
  }

  @Operation(
      operationId = "deleteTag",
      summary = "Delete a tag and remove it from every contact (OWNER, MANAGER)")
  @ApiResponse(responseCode = "204", description = "Tag deleted")
  @ApiResponse(responseCode = "400", description = "INVALID_INPUT: tagId")
  @ApiResponse(responseCode = "403", description = "CLINIC_ACCESS_DENIED")
  @ApiResponse(responseCode = "404", description = "TAG_NOT_FOUND")
  @ApiResponse(responseCode = "409", description = "CLINIC_NOT_SELECTED")
  @SecurityRequirement(name = "csrf")
  @DeleteMapping("/{tagId}")
  public ResponseEntity<Void> delete(
      @PathVariable UUID tagId,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    tags.delete(SelectedClinic.require(session), principal.subjectId(), tagId);
    return ResponseEntity.noContent().build();
  }
}
