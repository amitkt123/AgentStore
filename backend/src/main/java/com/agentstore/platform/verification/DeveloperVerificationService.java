package com.agentstore.platform.verification;

import com.agentstore.platform.common.AesGcmCipher;
import com.agentstore.platform.storage.ObjectStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@Service
public class DeveloperVerificationService {

    private static final Pattern PAN_PATTERN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");
    private static final Pattern GSTIN_PATTERN = Pattern.compile("^[0-9]{2}[A-Z0-9]{13}$");
    private static final Pattern PHONE_IN = Pattern.compile("^[6-9]\\d{9}$");
    private static final Pattern PHONE_E164 = Pattern.compile("^\\+\\d{10,15}$");
    private static final Set<String> ALLOWED_DOCUMENT_TYPES = Set.of("image/jpeg", "image/png", "application/pdf");
    private static final long MAX_DOCUMENT_BYTES = 5L * 1024 * 1024;
    private static final int MAX_LEGAL_NAME_LENGTH = 255;
    private static final int MAX_DISPLAY_NAME_LENGTH = 255;
    private static final int MAX_CITY_LENGTH = 100;

    private final DeveloperVerificationRepository repo;
    private final AesGcmCipher cipher;
    private final ObjectStorage storage;
    private final DeveloperVerificationRowCreator rowCreator;

    public DeveloperVerificationService(DeveloperVerificationRepository repo, AesGcmCipher cipher,
                                         ObjectStorage storage, DeveloperVerificationRowCreator rowCreator) {
        this.repo = repo;
        this.cipher = cipher;
        this.storage = storage;
        this.rowCreator = rowCreator;
    }

    @Transactional
    public DeveloperVerification getOrCreate(UUID orgId) {
        ensureRowExists(orgId);
        return repo.findByOrgId(orgId).orElseThrow();
    }

    /** Like {@link #getOrCreate}, but locks the row against concurrent writers for the rest of this transaction. */
    private DeveloperVerification lockOrCreate(UUID orgId) {
        ensureRowExists(orgId);
        return repo.findByOrgIdForUpdate(orgId).orElseThrow();
    }

    /**
     * Guarantees a row exists for {@code orgId} without letting a losing race poison this transaction. The
     * creation attempt runs in its own transaction (see {@link DeveloperVerificationRowCreator}); if it fails
     * because a concurrent first touch already created the row, this transaction — never having touched the
     * failing statement itself — simply re-checks and finds it. A failure that persists is a genuine bug and
     * is rethrown.
     */
    private void ensureRowExists(UUID orgId) {
        if (repo.findByOrgId(orgId).isPresent()) {
            return;
        }
        try {
            rowCreator.createIfAbsent(orgId);
        } catch (RuntimeException e) {
            if (repo.findByOrgId(orgId).isEmpty()) {
                throw e;
            }
        }
    }

    @Transactional
    public DeveloperVerification patchStep(UUID orgId, VerificationStep step, Map<String, String> fields) {
        if (step == null) {
            throw VerificationApiException.invalidField("step is required");
        }
        if (fields == null) {
            throw VerificationApiException.invalidField("fields is required");
        }
        DeveloperVerification v = lockOrCreate(orgId);
        requireEditable(v);
        switch (step) {
            case ABOUT -> applyAbout(v, fields);
            case TAX_IDS -> applyTaxIds(v, fields);
            case PHONE -> applyPhone(v, fields);
            case AGREEMENT -> applyAgreement(v, fields);
        }
        if (stepComplete(v, step)) {
            VerificationStep candidate = step.next();
            if (candidate.ordinal() > v.getCurrentStep().ordinal()) {
                v.setCurrentStep(candidate);
            }
        }
        return repo.save(v);
    }

    @Transactional
    public DeveloperVerification uploadPanDocument(UUID orgId, MultipartFile file) throws IOException {
        DeveloperVerification v = lockOrCreate(orgId);
        requireEditable(v);
        if (file.getSize() > MAX_DOCUMENT_BYTES) {
            throw VerificationApiException.documentTooLarge();
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_DOCUMENT_TYPES.contains(contentType)) {
            throw VerificationApiException.unsupportedDocumentType();
        }
        String key = "developer-verification/" + orgId + "/pan-document";
        storage.put(key, file.getBytes());
        v.setPanDocumentKey(key);
        return repo.save(v);
    }

    @Transactional
    public DeveloperVerification submit(UUID orgId) {
        DeveloperVerification v = lockOrCreate(orgId);
        requireEditable(v);
        List<String> missing = new ArrayList<>();
        if (!aboutComplete(v)) missing.add("ABOUT");
        if (v.getPanEncrypted() == null) missing.add("TAX_IDS");
        if (v.getPhoneEncrypted() == null) missing.add("PHONE");
        if (v.getAgreementAcceptedAt() == null) missing.add("AGREEMENT");
        if (v.getPanDocumentKey() == null) missing.add("PAN_DOCUMENT");
        if (!missing.isEmpty()) {
            throw VerificationApiException.incomplete(missing);
        }
        boolean wasFixRequested = v.getStatus() == VerificationStatus.FIX_REQUESTED;
        v.setStatus(VerificationStatus.PENDING);
        v.setSubmittedAt(Instant.now());
        if (wasFixRequested) {
            v.setResubmissionCount(v.getResubmissionCount() + 1);
        }
        return repo.save(v);
    }

    private void requireEditable(DeveloperVerification v) {
        if (v.getStatus() != VerificationStatus.DRAFT && v.getStatus() != VerificationStatus.FIX_REQUESTED) {
            throw VerificationApiException.locked();
        }
    }

    private void applyAbout(DeveloperVerification v, Map<String, String> fields) {
        if (fields.containsKey("legalName")) {
            v.setLegalName(nonBlank(fields.get("legalName"), "legalName", MAX_LEGAL_NAME_LENGTH));
        }
        if (fields.containsKey("displayName")) {
            v.setDisplayName(nonBlank(fields.get("displayName"), "displayName", MAX_DISPLAY_NAME_LENGTH));
        }
        if (fields.containsKey("country")) {
            String country = nonBlank(fields.get("country"), "country", 2);
            if (country.length() != 2) {
                throw VerificationApiException.invalidField("country must be a 2-letter code");
            }
            v.setCountry(country.toUpperCase(Locale.ROOT));
        }
        if (fields.containsKey("city")) v.setCity(nonBlank(fields.get("city"), "city", MAX_CITY_LENGTH));
        if (fields.containsKey("entityType")) {
            try {
                v.setEntityType(VerificationEntityType.valueOf(fields.get("entityType")));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw VerificationApiException.invalidField(
                        "entityType must be one of " + Arrays.toString(VerificationEntityType.values()));
            }
        }
    }

    private void applyTaxIds(DeveloperVerification v, Map<String, String> fields) {
        if (fields.containsKey("pan")) {
            String pan = fields.get("pan");
            if (pan == null || !PAN_PATTERN.matcher(pan).matches()) {
                throw VerificationApiException.invalidField("pan must match " + PAN_PATTERN.pattern());
            }
            encryptInto(v::setPanEncrypted, v::setPanNonce, v::setPanMasked, pan);
        }
        if (fields.containsKey("gstin")) {
            String gstin = fields.get("gstin");
            if (gstin != null && !gstin.isBlank()) {
                if (!GSTIN_PATTERN.matcher(gstin).matches()) {
                    throw VerificationApiException.invalidField("gstin must match " + GSTIN_PATTERN.pattern());
                }
                encryptInto(v::setGstinEncrypted, v::setGstinNonce, v::setGstinMasked, gstin);
            }
        }
    }

    private void applyPhone(DeveloperVerification v, Map<String, String> fields) {
        if (fields.containsKey("phone")) {
            String phone = fields.get("phone");
            if (phone == null || !(PHONE_IN.matcher(phone).matches() || PHONE_E164.matcher(phone).matches())) {
                throw VerificationApiException.invalidField("phone must be a valid Indian mobile or E.164 number");
            }
            encryptInto(v::setPhoneEncrypted, v::setPhoneNonce, v::setPhoneMasked, phone);
        }
    }

    private void applyAgreement(DeveloperVerification v, Map<String, String> fields) {
        if (fields.containsKey("agreementVersion")) {
            String version = fields.get("agreementVersion");
            if (!DeveloperAgreement.CURRENT_VERSION.equals(version)) {
                throw VerificationApiException.invalidField(
                        "agreementVersion must be " + DeveloperAgreement.CURRENT_VERSION);
            }
            v.setAgreementVersion(version);
            v.setAgreementAcceptedAt(java.time.Instant.now());
        }
    }

    private void encryptInto(Consumer<byte[]> setCipher, Consumer<byte[]> setNonce, Consumer<String> setMasked,
                             String plaintext) {
        AesGcmCipher.Encrypted encrypted = cipher.encrypt(plaintext.getBytes(StandardCharsets.UTF_8));
        setCipher.accept(encrypted.ciphertext());
        setNonce.accept(encrypted.nonce());
        setMasked.accept(last4(plaintext));
    }

    private static String last4(String value) {
        return value.length() < 8 ? "****" : value.substring(value.length() - 4);
    }

    private static String nonBlank(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw VerificationApiException.invalidField(field + " must not be blank");
        }
        if (value.length() > maxLength) {
            throw VerificationApiException.invalidField(field + " must be at most " + maxLength + " characters");
        }
        return value;
    }

    static boolean aboutComplete(DeveloperVerification v) {
        return isSet(v.getLegalName()) && isSet(v.getDisplayName()) && isSet(v.getCountry()) && isSet(v.getCity())
                && v.getEntityType() != null;
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean stepComplete(DeveloperVerification v, VerificationStep step) {
        return switch (step) {
            case ABOUT -> aboutComplete(v);
            case TAX_IDS -> v.getPanEncrypted() != null;
            case PHONE -> v.getPhoneEncrypted() != null;
            case AGREEMENT -> v.getAgreementAcceptedAt() != null;
        };
    }
}
