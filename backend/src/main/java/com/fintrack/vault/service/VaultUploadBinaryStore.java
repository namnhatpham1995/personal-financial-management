package com.fintrack.vault.service;

import java.io.IOException;

/**
 * Stores an upload's binary in GridFS tagged with {@code operationId}, so the stale-operation
 * recovery job can find/delete it if the process dies before the document save completes. Returns
 * the GridFS file id (hex string).
 */
@FunctionalInterface
public interface VaultUploadBinaryStore {

    String storeBinary(String operationId) throws IOException;
}
