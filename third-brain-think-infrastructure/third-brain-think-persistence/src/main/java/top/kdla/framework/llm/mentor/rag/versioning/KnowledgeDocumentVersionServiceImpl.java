package top.kdla.framework.llm.mentor.rag.versioning;

import top.kdla.framework.llm.mentor.rag.entity.KnowledgeDocument;
import top.kdla.framework.llm.mentor.rag.mapper.KnowledgeDocumentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 文档版本管理服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentVersionServiceImpl implements KnowledgeDocumentVersionService {

    private static final String STATUS_ACTIVE   = "ACTIVE";
    private static final String STATUS_INACTIVE = "INACTIVE";

    private final KnowledgeDocumentVersionMapper versionMapper;
    private final KnowledgeDocumentMapper        documentMapper;

    // ─────────────────────────────────────────────────────────────
    // Task 7.3 + 7.4: createVersion with SHA-256 dedup
    // ─────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public KnowledgeDocumentVersion createVersion(String docId, byte[] fileBytes, String changelog) {
        String hash = sha256(fileBytes);

        // Dedup: if a version with the same hash already exists, return it
        KnowledgeDocumentVersion existing = versionMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDocumentVersion>()
                        .eq(KnowledgeDocumentVersion::getDocId, docId)
                        .eq(KnowledgeDocumentVersion::getContentHash, hash)
                        .last("LIMIT 1")
        );
        if (existing != null) {
            log.info("Version with same content hash already exists for doc {}: versionId={}",
                    docId, existing.getVersionId());
            return existing;
        }

        // Determine next version number
        int nextVersion = versionMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeDocumentVersion>()
                        .eq(KnowledgeDocumentVersion::getDocId, docId)
        ).intValue() + 1;

        KnowledgeDocumentVersion newVersion = KnowledgeDocumentVersion.builder()
                .versionId(UUID.randomUUID().toString())
                .docId(docId)
                .version(nextVersion)
                .contentHash(hash)
                .status(STATUS_ACTIVE)
                .changelog(changelog)
                .createTime(LocalDateTime.now())
                .build();

        versionMapper.insert(newVersion);
        log.info("Created version {} for doc {}: versionId={}", nextVersion, docId, newVersion.getVersionId());

        // Auto-activate if this is the first version
        if (nextVersion == 1) {
            updateDocumentCurrentVersion(docId, newVersion.getVersionId());
        }

        return newVersion;
    }

    // ─────────────────────────────────────────────────────────────
    // Task 7.3: listVersions
    // ─────────────────────────────────────────────────────────────

    @Override
    public List<KnowledgeDocumentVersion> listVersions(String docId) {
        return versionMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocumentVersion>()
                        .eq(KnowledgeDocumentVersion::getDocId, docId)
                        .orderByDesc(KnowledgeDocumentVersion::getVersion)
        );
    }

    // ─────────────────────────────────────────────────────────────
    // Task 7.5: activateVersion
    // ─────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void activateVersion(String versionId) {
        KnowledgeDocumentVersion version = getVersionOrThrow(versionId);

        version.setStatus(STATUS_ACTIVE);
        versionMapper.updateById(version);

        // Update knowledge_document.current_version_id
        updateDocumentCurrentVersion(version.getDocId(), versionId);
        log.info("Activated version {} for doc {}", versionId, version.getDocId());
    }

    // ─────────────────────────────────────────────────────────────
    // Task 7.6: deactivateVersion — fallback to highest other ACTIVE version or NULL
    // ─────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void deactivateVersion(String versionId) {
        KnowledgeDocumentVersion version = getVersionOrThrow(versionId);
        version.setStatus(STATUS_INACTIVE);
        versionMapper.updateById(version);

        // Check if this was the current active version
        KnowledgeDocument doc = documentMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getDocId, version.getDocId())
        );
        if (doc == null || !versionId.equals(doc.getCurrentVersionId())) {
            log.info("Deactivated version {} (was not current version)", versionId);
            return;
        }

        // Find the highest ACTIVE version to fall back to
        List<KnowledgeDocumentVersion> activeVersions = versionMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocumentVersion>()
                        .eq(KnowledgeDocumentVersion::getDocId, version.getDocId())
                        .eq(KnowledgeDocumentVersion::getStatus, STATUS_ACTIVE)
                        .orderByDesc(KnowledgeDocumentVersion::getVersion)
        );

        String fallbackId = activeVersions.stream()
                .filter(v -> !versionId.equals(v.getVersionId()))
                .max(Comparator.comparingInt(KnowledgeDocumentVersion::getVersion))
                .map(KnowledgeDocumentVersion::getVersionId)
                .orElse(null);

        updateDocumentCurrentVersion(version.getDocId(), fallbackId);
        log.info("Deactivated version {}, fell back to {}", versionId, fallbackId);
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private KnowledgeDocumentVersion getVersionOrThrow(String versionId) {
        KnowledgeDocumentVersion v = versionMapper.selectById(versionId);
        if (v == null) throw new RuntimeException("Version not found: " + versionId);
        return v;
    }

    private void updateDocumentCurrentVersion(String docId, String versionId) {
        documentMapper.update(null,
                new LambdaUpdateWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getDocId, docId)
                        .set(KnowledgeDocument::getCurrentVersionId, versionId)
        );
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 computation failed", e);
        }
    }
}
