package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.service.*;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.model.User;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentPermissionTest {
    DocumentController controller; SharedContentAclService acl; OrgTagCacheService tags;
    @BeforeEach void setup() {
        controller=new DocumentController();acl=mock(SharedContentAclService.class);tags=mock(OrgTagCacheService.class);
        var users=mock(UserRepository.class);var user=new User();user.setId(2L);user.setUsername("B");when(users.findById(2L)).thenReturn(Optional.of(user));
        when(tags.getUserEffectiveOrgTags("B")).thenReturn(List.of("TEAM"));
        ReflectionTestUtils.setField(controller,"contentAcl",acl);ReflectionTestUtils.setField(controller,"orgTagCache",tags);
        ReflectionTestUtils.setField(controller,"permissionUserRepository",users);
    }
    @Test void privateUpdateUsesAuthenticatedOwnerAndNullOrganization() {
        assertEquals(200,controller.changePermissions("abc","2","USER",new DocumentController.PermissionUpdate(null,false)).getStatusCode().value());
        verify(acl).changePermissions("abc","2",null,false);
    }
    @Test void normalUserMayGrantOwnEffectiveOrganization() {
        assertEquals(200,controller.changePermissions("abc","2","USER",new DocumentController.PermissionUpdate("TEAM",false)).getStatusCode().value());
        verify(acl).changePermissions("abc","2","TEAM",false);
    }
    @Test void anotherOrganizationIsRejectedBeforeMutation() {
        assertEquals(403,controller.changePermissions("abc","2","USER",new DocumentController.PermissionUpdate("OTHER",false)).getStatusCode().value());
        verifyNoInteractions(acl);
    }
    @Test void explicitPublicValueIsRequired() {
        assertEquals(400,controller.changePermissions("abc","2","USER",new DocumentController.PermissionUpdate(null,null)).getStatusCode().value());
        verifyNoInteractions(acl);
    }
    @Test void serviceOwnerCheckFailurePreservesHttpStatus() {
        doThrow(new com.yizhaoqi.smartpai.exception.CustomException("不存在",org.springframework.http.HttpStatus.NOT_FOUND))
                .when(acl).changePermissions("abc","2",null,true);
        assertEquals(404,controller.changePermissions("abc","2","USER",new DocumentController.PermissionUpdate(null,true)).getStatusCode().value());
    }
}
