package com.yizhaoqi.smartpai.config;

import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.service.OrgTagCacheService;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SharedResourceAuthorizationTest {
    final String md5="f3845f9977bd2f0f31db7ca1100546d2";
    OrgTagAuthorizationFilter filter; FileUploadRepository files; JwtUtils jwt; OrgTagCacheService tags;
    @BeforeEach void setup() {
        filter=new OrgTagAuthorizationFilter();files=mock(FileUploadRepository.class);jwt=mock(JwtUtils.class);tags=mock(OrgTagCacheService.class);
        ReflectionTestUtils.setField(filter,"fileUploadRepository",files);ReflectionTestUtils.setField(filter,"jwtUtils",jwt);
        ReflectionTestUtils.setField(filter,"orgTagCacheService",tags);
        when(jwt.extractUserIdFromToken("token")).thenReturn("B");when(jwt.extractUsernameFromToken("token")).thenReturn("userB");
        when(tags.getUserEffectiveOrgTags("userB")).thenReturn(List.of("TEAM"));
    }
    FileUpload relation(String owner,String org,boolean published,int status) {
        var f=new FileUpload();f.setUserId(owner);f.setOrgTag(org);f.setPublic(published);f.setStatus(status);return f;
    }
    MockHttpServletResponse check(List<FileUpload> rows,boolean authenticated) throws Exception {
        when(files.findFirstByFileMd5OrderByCreatedAtDesc(md5)).thenReturn(Optional.of(rows.get(0)));
        when(files.findAllByFileMd5(md5)).thenReturn(rows);
        var request=new MockHttpServletRequest("GET","/api/v1/files/"+md5);if(authenticated)request.addHeader("Authorization","Bearer token");
        var response=new MockHttpServletResponse();filter.doFilter(request,response,new MockFilterChain());return response;
    }
    @Test void anyOwnerCanAccessRegardlessOfWhichUploaderIsLatest() throws Exception {
        assertEquals(200,check(List.of(relation("A","PRIVATE_A",false,1),relation("B",null,false,1)),true).getStatus());
    }
    @Test void organizationGrantMayComeFromAnyRelation() throws Exception {
        assertEquals(200,check(List.of(relation("A","PRIVATE_A",false,1),relation("C","TEAM",false,1)),true).getStatus());
    }
    @Test void nullAndDefaultOrgDoNotGrantAnonymousAccess() throws Exception {
        assertEquals(401,check(List.of(relation("A",null,false,1),relation("C","DEFAULT",false,1)),false).getStatus());
    }
    @Test void incompletePublicRelationDoesNotGrantAccess() throws Exception {
        assertEquals(403,check(List.of(relation("A",null,false,1),relation("C","TEAM",true,0)),true).getStatus());
    }
    @Test void completedPublicRelationGrantsAnonymousAccess() throws Exception {
        assertEquals(200,check(List.of(relation("A",null,false,1),relation("C",null,true,1)),false).getStatus());
    }
}
