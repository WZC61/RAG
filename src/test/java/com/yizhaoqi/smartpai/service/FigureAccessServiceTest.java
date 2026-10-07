package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.*;
import com.yizhaoqi.smartpai.parsing.description.FigureImageReader;
import com.yizhaoqi.smartpai.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FigureAccessServiceTest {
    static final String MD5 = "f3845f9977bd2f0f31db7ca1100546d2";
    UserRepository users; OrgTagCacheService orgs; FileUploadRepository files;
    FileContentRepository contents; DocumentFigureRepository figures;
    FigureImageReader images; FigureAccessService service; FileContent content; DocumentFigure figure;
    @BeforeEach void setup() throws Exception {
        users=mock(UserRepository.class); orgs=mock(OrgTagCacheService.class); files=mock(FileUploadRepository.class);
        contents=mock(FileContentRepository.class); figures=mock(DocumentFigureRepository.class); images=mock(FigureImageReader.class);
        service=new FigureAccessService(users,orgs,files,contents,figures,images);
        var user=new User(); user.setId(2L); user.setUsername("reader");
        when(users.findByUsername("reader")).thenReturn(Optional.of(user));
        when(orgs.getUserEffectiveOrgTags("reader")).thenReturn(List.of("TEAM"));
        when(files.existsAuthorizedCompletedContent(MD5,"2",List.of("TEAM"))).thenReturn(true);
        content=new FileContent(); content.setFileMd5(MD5); content.setProcessingGeneration(3L);
        content.setProcessingStatus(FileContent.ProcessingStatus.INDEXED);
        when(contents.findByFileMd5(MD5)).thenReturn(Optional.of(content));
        figure=new DocumentFigure(); figure.setFileMd5(MD5); figure.setProcessingGeneration(3L);
        figure.setPageNumber(4); figure.setFigureIndex(2); figure.setImagePath("figures/"+MD5+"/3/page-4-figure-2.jpg");
        when(figures.findByFileMd5AndProcessingGenerationAndPageNumberAndFigureIndex(MD5,3L,4,2)).thenReturn(Optional.of(figure));
        when(images.read(figure.getImagePath())).thenReturn(new FigureImageReader.FigureImage(new byte[]{1,2},"image/jpeg"));
    }
    void fails(int status, Runnable call) { assertEquals(status, assertThrows(ResponseStatusException.class,call::run).getStatusCode().value()); }
    @Test void readsOnlyThePathResolvedFromCurrentAuthorizedIdentity() throws Exception {
        assertEquals("image/jpeg",service.read("reader",MD5.toUpperCase(Locale.ROOT),3,4,2).mimeType());
        verify(images).read(figure.getImagePath()); verify(files,times(2)).existsAuthorizedCompletedContent(MD5,"2",List.of("TEAM"));
    }
    @Test void unauthenticatedCannotReachAnyStorage() { fails(401,()->service.read(null,MD5,3,4,2)); verifyNoInteractions(images,figures,files); }
    @Test void invalidIdentityIsBadRequest() { fails(400,()->service.read("reader","../uploads",3,4,2)); fails(400,()->service.read("reader",MD5,0,4,2)); verifyNoInteractions(images); }
    @Test void noCurrentGrantIsForbiddenEvenForOldReferences() {
        when(files.existsAuthorizedCompletedContent(MD5,"2",List.of("TEAM"))).thenReturn(false);
        fails(403,()->service.read("reader",MD5,3,4,2)); verifyNoInteractions(contents,figures,images);
    }
    @Test void wrongGenerationIsConflict() { fails(409,()->service.read("reader",MD5,2,4,2)); verifyNoInteractions(figures,images); }
    @Test void nonIndexedContentIsConflict() { content.setProcessingStatus(FileContent.ProcessingStatus.PARSED); fails(409,()->service.read("reader",MD5,3,4,2)); verifyNoInteractions(images); }
    @Test void deletedContentIsNotFound() { content.setDeletedAt(LocalDateTime.now()); fails(404,()->service.read("reader",MD5,3,4,2)); }
    @Test void missingFigureIsNotFound() {
        when(figures.findByFileMd5AndProcessingGenerationAndPageNumberAndFigureIndex(MD5,3L,4,2)).thenReturn(Optional.empty());
        fails(404,()->service.read("reader",MD5,3,4,2)); verifyNoInteractions(images);
    }
    @Test void corruptedPathCannotExposeAnotherContent() {
        figure.setImagePath("figures/another/3/page-4-figure-2.jpg"); fails(502,()->service.read("reader",MD5,3,4,2)); verifyNoInteractions(images);
    }
    @Test void corruptedPathCannotReturnAnotherFigureFromTheSameFile() {
        figure.setImagePath("figures/"+MD5+"/3/page-4-figure-1.jpg"); fails(502,()->service.read("reader",MD5,3,4,2)); verifyNoInteractions(images);
    }
    @Test void storageErrorsAreSanitized() throws Exception {
        when(images.read(anyString())).thenThrow(new IOException("signed-url-X-Amz-Signature=secret"));
        var error=assertThrows(ResponseStatusException.class,()->service.read("reader",MD5,3,4,2));
        assertEquals(502,error.getStatusCode().value()); assertFalse(error.toString().contains("secret")); assertNull(error.getCause());
    }
    @Test void revokedWhileDownloadingCannotReturnBytes() {
        when(files.existsAuthorizedCompletedContent(MD5,"2",List.of("TEAM"))).thenReturn(true,false);
        fails(403,()->service.read("reader",MD5,3,4,2));
    }
    @Test void generationChangedWhileDownloadingCannotReturnBytes() throws Exception {
        when(images.read(anyString())).thenAnswer(call->{content.setProcessingGeneration(4L);return new FigureImageReader.FigureImage(new byte[]{1},"image/png");});
        fails(409,()->service.read("reader",MD5,3,4,2));
    }
}
