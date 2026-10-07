package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.FileContent;
import com.yizhaoqi.smartpai.parsing.description.FigureImageReader;
import com.yizhaoqi.smartpai.repository.DocumentFigureRepository;
import com.yizhaoqi.smartpai.repository.FileContentRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Images are located by business identity and authorized against current MySQL relations. */
@Service
public class FigureAccessService {
    private final UserRepository users;
    private final OrgTagCacheService organizations;
    private final FileUploadRepository files;
    private final FileContentRepository contents;
    private final DocumentFigureRepository figures;
    private final FigureImageReader images;

    public FigureAccessService(UserRepository users, OrgTagCacheService organizations, FileUploadRepository files,
            FileContentRepository contents, DocumentFigureRepository figures, FigureImageReader images) {
        this.users = users; this.organizations = organizations; this.files = files;
        this.contents = contents; this.figures = figures; this.images = images;
    }

    public FigureImageReader.FigureImage read(String username, String md5, long generation, int page, int index) {
        if (username == null || username.isBlank()) throw failure(HttpStatus.UNAUTHORIZED, "请先登录");
        if (md5 == null || !md5.matches("[a-fA-F0-9]{32}") || generation < 1 || page < 1 || index < 1)
            throw failure(HttpStatus.BAD_REQUEST, "Figure 业务身份不合法");
        String fileMd5 = md5.toLowerCase(Locale.ROOT);
        var user = users.findByUsername(username)
                .orElseThrow(() -> failure(HttpStatus.UNAUTHORIZED, "用户不存在"));
        List<String> tags = organizations.getUserEffectiveOrgTags(user.getUsername());
        checkAccess(fileMd5, user.getId().toString(), tags);
        checkGeneration(fileMd5, generation);
        var figure = figures.findByFileMd5AndProcessingGenerationAndPageNumberAndFigureIndex(
                fileMd5, generation, page, index)
                .orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "Figure 不存在"));
        // Even a corrupted DB path must not expose another content's image.
        String prefix = "figures/" + fileMd5 + "/" + generation + "/page-" + page + "-figure-" + index;
        if (figure.getImagePath() == null || !(figure.getImagePath().equals(prefix + ".jpg")
                || figure.getImagePath().equals(prefix + ".png") || figure.getImagePath().equals(prefix + ".webp")))
            throw failure(HttpStatus.BAD_GATEWAY, "Figure 存储信息异常");
        try {
            var image = images.read(figure.getImagePath());
            // No long DB transaction around object I/O; recheck after the download as well.
            checkAccess(fileMd5, user.getId().toString(), organizations.getUserEffectiveOrgTags(user.getUsername()));
            checkGeneration(fileMd5, generation);
            return image;
        } catch (IOException error) {
            throw failure(HttpStatus.BAD_GATEWAY, "Figure 图片不存在或存储读取失败");
        }
    }

    private void checkAccess(String md5, String userId, List<String> tags) {
        if (!files.existsAuthorizedCompletedContent(md5, userId, tags))
            throw failure(HttpStatus.FORBIDDEN, "无权限访问该 Figure");
    }

    private void checkGeneration(String md5, long generation) {
        var content = contents.findByFileMd5(md5)
                .orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "文件内容不存在"));
        if (content.getDeletedAt() != null) throw failure(HttpStatus.NOT_FOUND, "文件内容已删除");
        if (!Objects.equals(content.getProcessingGeneration(), generation))
            throw failure(HttpStatus.CONFLICT, "Figure 处理代次已失效，请使用当前引用");
        if (content.getProcessingStatus() != FileContent.ProcessingStatus.INDEXED)
            throw failure(HttpStatus.CONFLICT, "文件内容尚未完成索引");
    }

    private static ResponseStatusException failure(HttpStatus status, String reason) {
        return new ResponseStatusException(status, reason);
    }
}
