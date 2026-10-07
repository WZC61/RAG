package com.yizhaoqi.smartpai.model;

import com.yizhaoqi.smartpai.entity.EsDocument;
import java.util.List;
import java.util.TreeSet;

/** A projection of completed user/content relations, never an independent permission source. */
public record ContentAcl(List<String> allowedUserIds, List<String> allowedOrgTags, boolean isPublic) {
    public static ContentAcl from(List<FileUpload> relations) {
        var users = new TreeSet<String>();
        var orgs = new TreeSet<String>();
        boolean published = false;
        for (FileUpload relation : relations) {
            if (relation.getStatus() != FileUpload.STATUS_COMPLETED) continue;
            if (relation.getUserId() != null && !relation.getUserId().isBlank()) users.add(relation.getUserId());
            String org = relation.getOrgTag();
            // PRIVATE_* is an owner-only space, not an organization grant.
            if (org != null && !org.isBlank() && !org.startsWith("PRIVATE_")) orgs.add(org);
            published |= relation.isPublic();
        }
        return new ContentAcl(List.copyOf(users), List.copyOf(orgs), published);
    }

    public void apply(EsDocument document) {
        document.setAllowedUserIds(allowedUserIds);
        document.setAllowedOrgTags(allowedOrgTags);
        document.setPublic(isPublic);
    }
}
