package com.shanzhu.beadhouse.service.common;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.shanzhu.beadhouse.common.config.exception.BusinessRuntimeException;
import com.shanzhu.beadhouse.common.config.security.handler.AuthorityAssert;
import com.shanzhu.beadhouse.dao.mapper.*;
import com.shanzhu.beadhouse.entity.po.*;
import com.shanzhu.beadhouse.entity.vo.LoginUserVo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Attachment authority comes from the live business record, never from a supplied elder ID. */
@Service
public class AttachmentAccessService {
    public static final String DRAFT = "DRAFT";
    public static final String BOUND = "BOUND";
    public static final String LEGACY = "LEGACY";
    public static final String CONFLICT = "CONFLICT";
    private static final Pattern CANONICAL = Pattern.compile("^/api/file/([1-9][0-9]*)/content$");
    private static final Set<String> OWNER_TYPES = new HashSet<>(Arrays.asList("STAFF", "ACCIDENT", "ACTIVE"));

    @Resource private BaseAttachmentMapper attachmentMapper;
    @Resource private AttachmentReferenceMapper referenceMapper;
    @Resource private StaffMapper staffMapper;
    @Resource private AccidentMapper accidentMapper;
    @Resource private ActiveMapper activeMapper;
    @Resource private ActiveParticipantMapper participantMapper;
    @Resource private AuthMapper authMapper;
    @Resource private AuthorityAssert authorityAssert;
    @Resource private AiDataScopeService dataScope;
    @Value("${ai.data-scope.admin-role-id:1}") private long adminRoleId;

    public static String canonicalUrl(Long attachmentId) {
        if (attachmentId == null || attachmentId <= 0) throw new IllegalArgumentException("Missing attachment ID");
        return "/api/file/" + attachmentId + "/content";
    }

    public BaseAttachment assertReadable(Long attachmentId) {
        LoginUserVo user = requireLogin();
        BaseAttachment attachment = attachmentId == null ? null : attachmentMapper.selectById(attachmentId);
        requireAvailable(attachment);
        if (dataScope.isAdmin()) return attachment;
        if (DRAFT.equals(attachment.getBindingState())) {
            if (Objects.equals(user.getId(), attachment.getCreateId())
                    && referenceMapper.selectCount(new QueryWrapper<AttachmentReference>().eq("attachment_id", attachmentId)) == 0) {
                return attachment;
            }
            throw forbidden();
        }
        if (!BOUND.equals(attachment.getBindingState())) throw forbidden();
        List<AttachmentReference> references = referenceMapper.selectList(
                new QueryWrapper<AttachmentReference>().eq("attachment_id", attachmentId));
        // Even an inactive second owner is a conflicting history, not an alternative grant.
        if (references.size() != 1 || !"Y".equals(references.get(0).getActive())) throw forbidden();
        AttachmentReference reference = references.get(0);
        String currentUrl = authorizeOwner(reference.getOwnerType(), reference.getOwnerId());
        if (!refersTo(currentUrl, attachment)) throw forbidden();
        return attachment;
    }

    /** Exact metadata lookup only. No HTTP fetch, host rewriting, basename matching, or disk search. */
    public BaseAttachment resolveReadable(String url) {
        requireLogin();
        List<BaseAttachment> matches = lookup(url);
        if (matches.size() != 1) throw new BusinessRuntimeException(404, "附件未找到或需要核对");
        return assertReadable(matches.get(0).getId());
    }

    /** The caller locks its business row first and persists the returned URL in this same transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String bind(String ownerType, Long ownerId, String previousUrl, String requestedUrl) {
        LoginUserVo user = requireLogin();
        requireOwner(ownerType, ownerId);
        String currentUrl = authorizeOwner(ownerType, ownerId);
        if (!Objects.equals(empty(currentUrl), empty(previousUrl))) {
            throw new BusinessRuntimeException(409, "业务图片已变化，请刷新后重试");
        }
        List<BaseAttachment> matches = lookup(requestedUrl);
        // Preserve an old unknown value while editing unrelated fields. This does not grant access or claim it.
        if (Objects.equals(empty(previousUrl), empty(requestedUrl))
                && (matches.size() != 1 || LEGACY.equals(matches.get(0).getBindingState())
                || CONFLICT.equals(matches.get(0).getBindingState()))) {
            return empty(requestedUrl);
        }
        boolean clearing = requestedUrl == null || requestedUrl.isEmpty();
        if (!clearing && matches.size() != 1) throw forbidden();
        Long requestedId = clearing ? null : matches.get(0).getId();
        List<AttachmentReference> activeReferences = activeOwnerReferences(ownerType, ownerId);
        Map<Long, BaseAttachment> locked = lockAttachments(activeReferences, requestedId);

        if (requestedId != null) {
            BaseAttachment attachment = locked.get(requestedId);
            requireAvailable(attachment);
            // Recheck after waiting for the row lock; a competing owner may have claimed it meanwhile.
            List<AttachmentReference> history = referenceMapper.lockByAttachment(requestedId);
            if (DRAFT.equals(attachment.getBindingState()) && history.isEmpty()) {
                if (!Objects.equals(user.getId(), attachment.getCreateId())) throw forbidden();
                if (attachmentMapper.claimDraft(requestedId, user.getId()) != 1) {
                    throw new BusinessRuntimeException(409, "附件状态已变化，请重新上传");
                }
                AttachmentReference reference = new AttachmentReference();
                reference.setAttachmentId(requestedId);
                reference.setOwnerType(ownerType);
                reference.setOwnerId(ownerId);
                reference.setActive("Y");
                if (referenceMapper.insert(reference) != 1) throw new IllegalStateException("Attachment reference was not saved");
            } else if (BOUND.equals(attachment.getBindingState()) && history.size() == 1
                    && sameOwner(history.get(0), ownerType, ownerId)) {
                setActive(history.get(0), "Y", user.getId());
            } else {
                throw forbidden();
            }
        }
        for (AttachmentReference reference : activeReferences) {
            if (!Objects.equals(reference.getAttachmentId(), requestedId)) setActive(reference, "N", user.getId());
        }
        return requestedId == null ? "" : canonicalUrl(requestedId);
    }

    /** Called after the business operation has authorized and locked its owner, including soft deletion. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void retire(String ownerType, Long ownerId) {
        Long operatorId = requireLogin().getId();
        requireOwner(ownerType, ownerId);
        List<AttachmentReference> references = activeOwnerReferences(ownerType, ownerId);
        lockAttachments(references, null);
        for (AttachmentReference reference : references) setActive(reference, "N", operatorId);
    }

    public void assertAdministrator() {
        requireLogin();
        if (!dataScope.isAdmin()) throw forbidden();
    }

    private Map<Long, BaseAttachment> lockAttachments(List<AttachmentReference> references, Long requestedId) {
        Set<Long> ids = new TreeSet<>();
        for (AttachmentReference reference : references) ids.add(reference.getAttachmentId());
        if (requestedId != null) ids.add(requestedId);
        Map<Long, BaseAttachment> result = new HashMap<>();
        for (Long id : ids) result.put(id, attachmentMapper.lockById(id));
        return result;
    }

    private List<AttachmentReference> activeOwnerReferences(String ownerType, Long ownerId) {
        return referenceMapper.selectList(new QueryWrapper<AttachmentReference>()
                .eq("owner_type", ownerType).eq("owner_id", ownerId).eq("active", "Y").orderByAsc("attachment_id"));
    }

    private void setActive(AttachmentReference reference, String active, Long operatorId) {
        if (!active.equals(reference.getActive()) && referenceMapper.setActive(reference.getId(), active, operatorId) != 1) {
            throw new IllegalStateException("Attachment reference was not updated");
        }
    }

    private List<BaseAttachment> lookup(String url) {
        if (url == null || url.isEmpty()) return Collections.emptyList();
        Matcher matcher = CANONICAL.matcher(url);
        if (matcher.matches()) {
            try {
                BaseAttachment attachment = attachmentMapper.selectById(Long.valueOf(matcher.group(1)));
                return attachment == null ? Collections.emptyList() : Collections.singletonList(attachment);
            } catch (NumberFormatException invalid) {
                return Collections.emptyList();
            }
        }
        if (url.length() > 225 || url.chars().anyMatch(Character::isISOControl)) return Collections.emptyList();
        return attachmentMapper.findExactUrl(url);
    }

    private String authorizeOwner(String ownerType, Long ownerId) {
        requireOwner(ownerType, ownerId);
        LoginUserVo user = requireLogin();
        if ("STAFF".equals(ownerType)) {
            Staff staff = staffMapper.selectById(ownerId);
            if (staff == null || !"N".equals(staff.getLeaveFlag())) throw forbidden();
            if (!Objects.equals(user.getId(), ownerId) && !dataScope.isAdmin()) {
                if (staff.getRoleId() != null && staff.getRoleId() == adminRoleId) throw forbidden();
                assertModule("/people/staff");
            }
            return staff.getAvator();
        }
        if ("ACCIDENT".equals(ownerType)) {
            Accident accident = accidentMapper.selectById(ownerId);
            if (accident == null || !"N".equals(accident.getDelFlag())) throw forbidden();
            assertModule("/check-in/accident");
            dataScope.assertElderAccess(accident.getElderId());
            return accident.getPicture();
        }
        Active activity = activeMapper.selectById(ownerId);
        if (activity == null || !"N".equals(activity.getDelFlag())) throw forbidden();
        assertModule("/people/activity");
        for (ActiveParticipant participant : participantMapper.selectList(
                new QueryWrapper<ActiveParticipant>().eq("active_id", ownerId))) {
            dataScope.assertElderAccess(participant.getElderId());
        }
        return activity.getActivePicture();
    }

    private void assertModule(String module) {
        if (dataScope.isAdmin()) return;
        LoginUserVo user = requireLogin();
        // Check current role grants, not the authority list cached when the user logged in.
        if (user.getRoleId() == null || authMapper.listAuthByRoleId(user.getRoleId()).stream()
                .noneMatch(auth -> module.equals(auth.getUrl()))) throw forbidden();
    }

    private LoginUserVo requireLogin() {
        LoginUserVo user = authorityAssert.getLoginUserInfo();
        if (user == null) throw new BusinessRuntimeException(401, "请先登录");
        return user;
    }

    private void requireOwner(String type, Long id) {
        if (!OWNER_TYPES.contains(type) || id == null || id <= 0) {
            throw new BusinessRuntimeException(400, "附件业务归属无效");
        }
    }

    private void requireAvailable(BaseAttachment attachment) {
        if (attachment == null || !"N".equals(attachment.getDelFlag())) {
            throw new BusinessRuntimeException(404, "附件不存在");
        }
    }

    private boolean sameOwner(AttachmentReference reference, String type, Long id) {
        return type.equals(reference.getOwnerType()) && Objects.equals(id, reference.getOwnerId());
    }

    private boolean refersTo(String url, BaseAttachment attachment) {
        return url != null && (url.equals(attachment.getUrl()) || url.equals(canonicalUrl(attachment.getId())));
    }

    private String empty(String value) { return value == null ? "" : value; }
    private BusinessRuntimeException forbidden() { return new BusinessRuntimeException(403, "无权访问或绑定该附件"); }
}
