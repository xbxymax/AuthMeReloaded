package fr.xephi.authme.service;

import fr.xephi.authme.datasource.DataSource;
import fr.xephi.authme.initialization.Reloadable;
import fr.xephi.authme.mail.EmailService;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.message.Messages;
import fr.xephi.authme.security.PasswordSecurity;
import fr.xephi.authme.security.crypts.HashedPassword;
import fr.xephi.authme.settings.properties.SecuritySettings;
import fr.xephi.authme.util.RandomStringUtils;
import fr.xephi.authme.util.expiring.Duration;
import fr.xephi.authme.util.expiring.ExpiringSet;
import org.bukkit.entity.Player;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import java.util.concurrent.TimeUnit;

import static fr.xephi.authme.settings.properties.EmailSettings.RECOVERY_PASSWORD_LENGTH;

/**
 * Manager for password recovery.
 */
public class PasswordRecoveryService implements Reloadable {

    @Inject
    private CommonService commonService;

    @Inject
    private RecoveryCodeService codeService;

    @Inject
    private DataSource dataSource;

    @Inject
    private EmailService emailService;

    @Inject
    private PasswordSecurity passwordSecurity;

    @Inject
    private RecoveryCodeService recoveryCodeService;

    @Inject
    private Messages messages;

    private ExpiringSet<String> emailCooldown;

    @PostConstruct
    private void initEmailCooldownSet() {
        emailCooldown = new ExpiringSet<>(
            commonService.getProperty(SecuritySettings.EMAIL_RECOVERY_COOLDOWN_SECONDS), TimeUnit.SECONDS);
    }

    /**
     * Create a new recovery code and send it to the player
     * via email.
     *
     * @param player The player getting the code.
     * @param email The email to send the code to.
     */
    public void createAndSendRecoveryCode(Player player, String email) {
        if (!checkEmailCooldown(player)) {
            return;
        }

        String recoveryCode = recoveryCodeService.generateCode(player.getName());
        boolean couldSendMail = emailService.sendRecoveryCode(player.getName(), email, recoveryCode);
        if (couldSendMail) {
            commonService.send(player, MessageKey.RECOVERY_CODE_SENT);
            emailCooldown.add(player.getName().toLowerCase());
        } else {
            commonService.send(player, MessageKey.EMAIL_SEND_FAILURE);
        }
    }

    /**
     * Generate a new password and send it to the player via
     * email. This will update the database with the new password.
     *
     * @param player The player recovering their password.
     * @param email The email to send the password to.
     */
    public void generateAndSendNewPassword(Player player, String email) {
        if (!checkEmailCooldown(player)) {
            return;
        }

        String name = player.getName();
        String thePass = RandomStringUtils.generate(commonService.getProperty(RECOVERY_PASSWORD_LENGTH));
        HashedPassword hashNew = passwordSecurity.computeHash(thePass, name);

        dataSource.updatePassword(name, hashNew);
        boolean couldSendMail = emailService.sendPasswordMail(name, email, thePass);
        if (couldSendMail) {
            commonService.send(player, MessageKey.RECOVERY_EMAIL_SENT_MESSAGE);
            emailCooldown.add(player.getName().toLowerCase());
        } else {
            commonService.send(player, MessageKey.EMAIL_SEND_FAILURE);
        }
    }

    /**
     * Check if a player is able to have emails sent.
     *
     * @param player The player to check.
     * @return True if the player is not on cooldown.
     */
    public boolean checkEmailCooldown(Player player) {
        Duration waitDuration = emailCooldown.getExpiration(player.getName().toLowerCase());
        if (waitDuration.getDuration() > 0) {
            String durationText = messages.formatDuration(waitDuration);
            messages.send(player, MessageKey.EMAIL_COOLDOWN_ERROR, durationText);
            return false;
        }
        return true;
    }

    @Override
    public void reload() {
        emailCooldown.setExpiration(
            commonService.getProperty(SecuritySettings.EMAIL_RECOVERY_COOLDOWN_SECONDS), TimeUnit.SECONDS);
    }
}