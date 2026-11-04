package com.shanzhu.beadhouse.service.impl;

import com.shanzhu.beadhouse.dao.mapper.PolicyDocMapper;
import com.shanzhu.beadhouse.entity.po.PolicyDoc;
import com.shanzhu.beadhouse.entity.po.PolicyRagSync;
import com.shanzhu.beadhouse.entity.vo.PolicyCatalogVo;
import com.shanzhu.beadhouse.service.common.PolicyRagSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PolicyCatalogDuplicatesTest {
    private final PolicyDocMapper mapper = mock(PolicyDocMapper.class);
    private final PolicyRagSyncService sync = mock(PolicyRagSyncService.class);
    private final AiPolicyServiceImpl service = new AiPolicyServiceImpl();

    @BeforeEach void setUp() {
        ReflectionTestUtils.setField(service, "policyDocMapper", mapper);
        ReflectionTestUtils.setField(service, "ragSyncService", sync);
    }

    @Test void comparesWholeOrderedContentAcrossTitlesAndSources() {
        when(mapper.selectList(any())).thenReturn(Arrays.asList(
                doc("A", "one", 2, "第二段  \n"), doc("A", "one", 1, "第一行\r\n第二行"),
                doc("B", "two", 1, "第一行\n第二行"), doc("B", "two", 2, "第二段"),
                doc("A", "three", 2, "第二段"), doc("A", "three", 1, "第一行\n第二行")));
        List<PolicyCatalogVo> items = catalog();
        assertThat(items).extracting(PolicyCatalogVo::getTitle).containsExactly("A", "B", "A");
        assertThat(items).extracting(PolicyCatalogVo::getSectionCount).containsExactly(2, 2, 2);
        assertThat(items).extracting(PolicyCatalogVo::getDuplicateCount).containsExactly(2, 2, 2);
        assertThat(items.get(0).getContentHash()).matches("[a-f0-9]{64}");
        assertThat(items).extracting(PolicyCatalogVo::getContentHash).containsOnly(items.get(0).getContentHash());
        verify(mapper, never()).delete(any());
        verify(mapper, never()).insert(any());
    }

    @Test void titleMatchPartialMatchNumbersWhitespaceAndOrderDoNotImplyDuplicates() {
        when(mapper.selectList(any())).thenReturn(Arrays.asList(
                doc("A", "one", 1, "阈值1 2"), doc("A", "one", 2, "确认"),
                doc("A", "two", 1, "阈值12"), doc("A", "two", 2, "确认"),
                doc("B", "three", 1, "阈值1 2"), doc("B", "three", 2, "变化"),
                doc("C", "four", 1, "确认"), doc("C", "four", 2, "阈值1 2"),
                doc("D", "five", 1, "阈值1 3"), doc("D", "five", 2, "确认"),
                doc("E", "six", 1, "阈值1 2确认")));
        List<PolicyCatalogVo> items = catalog();
        assertThat(items).extracting(PolicyCatalogVo::getDuplicateCount).containsOnly(0);
        assertThat(items).extracting(PolicyCatalogVo::getContentHash).doesNotHaveDuplicates();
    }

    @Test void everyReadReflectsCurrentReplacementAndDeletion() {
        PolicyDoc a = doc("A", "one", 1, "相同正文");
        PolicyDoc b = doc("B", "two", 1, "相同正文");
        when(mapper.selectList(any())).thenReturn(Arrays.asList(a, b),
                Arrays.asList(a, doc("B", "two", 1, "更新正文")), Arrays.asList(a, b), Collections.singletonList(a));
        List<PolicyCatalogVo> initial = catalog();
        assertThat(initial).extracting(PolicyCatalogVo::getDuplicateCount).containsExactly(1, 1);
        List<PolicyCatalogVo> updated = catalog();
        assertThat(updated).extracting(PolicyCatalogVo::getDuplicateCount).containsExactly(0, 0);
        assertThat(updated.get(1).getContentHash()).isNotEqualTo(initial.get(1).getContentHash());
        assertThat(catalog()).extracting(PolicyCatalogVo::getDuplicateCount).containsExactly(1, 1);
        assertThat(catalog()).extracting(PolicyCatalogVo::getDuplicateCount).containsExactly(0);
    }

    @Test void retainsRagStatusAndHandlesEmptyCatalog() {
        PolicyRagSync state = new PolicyRagSync();
        state.setStatus("FAILED"); state.setAttemptCount(3); state.setLastErrorCode("TIMEOUT");
        when(sync.documentKey("A", "one")).thenReturn("key");
        when(sync.find("key")).thenReturn(state);
        when(mapper.selectList(any())).thenReturn(Collections.singletonList(doc("A", "one", 1, "正文")), Collections.emptyList());
        PolicyCatalogVo item = catalog().get(0);
        assertThat(item.getRagStatus()).isEqualTo("FAILED");
        assertThat(item.getRagAttemptCount()).isEqualTo(3);
        assertThat(item.getRagLastErrorCode()).isEqualTo("TIMEOUT");
        assertThat(item.getDuplicateCount()).isZero();
        assertThat(catalog()).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private List<PolicyCatalogVo> catalog() {
        return (List<PolicyCatalogVo>) service.listDocuments().getData();
    }

    private PolicyDoc doc(String title, String source, int section, String content) {
        PolicyDoc doc = new PolicyDoc();
        doc.setTitle(title); doc.setSource(source); doc.setSectionNo(section); doc.setContent(content);
        return doc;
    }
}
