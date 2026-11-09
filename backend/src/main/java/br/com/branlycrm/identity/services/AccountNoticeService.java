package br.com.branlycrm.identity.services;

import br.com.branlycrm.identity.domain.AccountNotice;
import br.com.branlycrm.identity.mail.MailMessage;
import br.com.branlycrm.identity.mail.MailSender;
import br.com.branlycrm.identity.repositories.AccountNoticeRepository;
import br.com.branlycrm.identity.security.CrmPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

/**
 * Delivers pending account notices at login (V14). The platform command has no mail credential, so
 * it leaves the notice in the database and the API sends it the next time the account logs in.
 * There is no background job: an account that never logs in again is not warned. A notice is
 * claimed before sending and given back when the e-mail fails; nothing here fails the login.
 */
@ConditionalOnWebApplication
@Service
public class AccountNoticeService {
  private static final Logger log = LoggerFactory.getLogger(AccountNoticeService.class);

  private final AccountNoticeRepository notices;
  private final MailSender mail;

  public AccountNoticeService(AccountNoticeRepository notices, MailSender mail) {
    this.notices = notices;
    this.mail = mail;
  }

  public void deliverPending(CrmPrincipal principal) {
    try {
      for (AccountNotice notice : notices.pending(principal.subjectId())) {
        if (!notices.claim(notice.noticeId(), principal.subjectId())) continue;
        if (!send(principal, notice)) {
          notices.release(notice.noticeId(), principal.subjectId());
          log.warn("Account notice not delivered: ACCOUNT_NOTICE_FAILED");
        }
      }
    } catch (RuntimeException failure) {
      log.warn("Account notices unavailable: ACCOUNT_NOTICE_UNAVAILABLE");
    }
  }

  private boolean send(CrmPrincipal principal, AccountNotice notice) {
    if (!"MFA_RESET".equals(notice.kind())) return true;
    try {
      return mail.send(
          new MailMessage(
              principal.getUsername(),
              "Segundo fator redefinido",
              "A equipe da plataforma removeu o segundo fator da sua conta a pedido de recuperação"
                  + " e encerrou todas as sessões. Cadastre um novo aplicativo autenticador. Se"
                  + " não foi você que pediu, avise a equipe da plataforma imediatamente."));
    } catch (RuntimeException failure) {
      return false;
    }
  }
}
