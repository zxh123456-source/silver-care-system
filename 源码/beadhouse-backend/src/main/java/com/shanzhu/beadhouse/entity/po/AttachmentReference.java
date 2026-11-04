package com.shanzhu.beadhouse.entity.po;

import com.shanzhu.beadhouse.entity.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Permanent provenance. Replacing a picture retires its reference instead of erasing it. */
@Data
@EqualsAndHashCode(callSuper = true)
public class AttachmentReference extends BaseEntity {
    private Long attachmentId;
    private String ownerType;
    private Long ownerId;
    private String active;
}
