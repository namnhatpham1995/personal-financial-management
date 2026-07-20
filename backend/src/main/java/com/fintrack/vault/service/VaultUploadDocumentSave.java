package com.fintrack.vault.service;

/**
 * Persists the domain document referencing {@code gridFsFileId} and builds the business response.
 * If this throws, the coordinator deletes the just-stored GridFS binary (compensation) before
 * propagating the exception.
 */
@FunctionalInterface
public interface VaultUploadDocumentSave<T> {

    VaultUploadResult<T> saveDocument(String gridFsFileId);
}
