package com.fintrack.vault.service;

import com.mongodb.client.gridfs.model.GridFSFile;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Service
@RequiredArgsConstructor
public class GridFsFileStore {

    private final GridFsTemplate gridFsTemplate;

    /**
     * Stores a binary file and returns its GridFS ObjectId as a hex string.
     * Metadata field {@code userId} restricts retrieval to the owning user.
     */
    public String store(MultipartFile file, Long userId) throws IOException {
        ObjectId id = gridFsTemplate.store(
                file.getInputStream(),
                file.getOriginalFilename(),
                file.getContentType(),
                new org.bson.Document("userId", userId)
        );
        return id.toHexString();
    }

    /**
     * Stores a binary file tagged with the owning {@code VaultOperation}'s id, so a stale-operation
     * recovery sweep can find and delete an orphaned binary belonging to a specific operation.
     */
    public String store(MultipartFile file, Long userId, String operationId) throws IOException {
        ObjectId id = gridFsTemplate.store(
                file.getInputStream(),
                file.getOriginalFilename(),
                file.getContentType(),
                new org.bson.Document("userId", userId).append("operationId", operationId)
        );
        return id.toHexString();
    }

    /**
     * Loads a GridFS resource scoped to {@code userId}. Returns null if not found
     * or if the file belongs to a different user (prevents cross-user access).
     */
    public GridFsResource load(String fileId, Long userId) {
        GridFSFile file = gridFsTemplate.findOne(
                Query.query(Criteria.where("_id").is(new ObjectId(fileId))
                        .and("metadata.userId").is(userId))
        );
        if (file == null) {
            return null;
        }
        return gridFsTemplate.getResource(file);
    }

    public void delete(String fileId) {
        gridFsTemplate.delete(Query.query(Criteria.where("_id").is(new ObjectId(fileId))));
    }
}
