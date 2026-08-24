package com.timingjeju.api.domain.legal.controller;

import com.timingjeju.api.application.legal.service.LegalDocumentService;
import com.timingjeju.api.application.legal.service.UserConsentService;
import com.timingjeju.api.application.security.CurrentUserAccessor;
import com.timingjeju.api.domain.legal.controller.docs.LegalProfileApiDocs;
import com.timingjeju.api.domain.legal.dto.request.UserConsentsRequest;
import com.timingjeju.api.domain.legal.dto.response.LegalDocumentsResponse;
import com.timingjeju.api.domain.legal.dto.response.UserConsentsResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LegalProfileController implements LegalProfileApiDocs {

  private final LegalDocumentService documents;
  private final UserConsentService consents;
  private final CurrentUserAccessor currentUsers;

  public LegalProfileController(
      LegalDocumentService documents,
      UserConsentService consents,
      CurrentUserAccessor currentUsers) {
    this.documents = documents;
    this.consents = consents;
    this.currentUsers = currentUsers;
  }

  @Override
  @GetMapping("/api/v1/legal-documents")
  public LegalDocumentsResponse read(@RequestParam(required = false) String locale) {
    return LegalDocumentsResponse.from(documents.read(locale));
  }

  @Override
  @PutMapping("/api/v1/me/consents")
  public UserConsentsResponse update(@RequestBody UserConsentsRequest request) {
    return UserConsentsResponse.from(
        consents.update(currentUsers.getRequired(), request.toDecisions()));
  }
}
