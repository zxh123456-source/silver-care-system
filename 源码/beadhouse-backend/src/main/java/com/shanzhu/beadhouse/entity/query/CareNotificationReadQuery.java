package com.shanzhu.beadhouse.entity.query;

import lombok.Data;

/** Staff identity is always taken from the authenticated session. */
@Data
public class CareNotificationReadQuery {
    private Long id;
    private Long noticeRevision;
}
