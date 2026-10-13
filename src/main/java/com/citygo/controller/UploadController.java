package com.citygo.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.citygo.dto.Result;
import com.citygo.dto.UserDTO;
import com.citygo.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static com.citygo.utils.RedisConstants.UPLOAD_BLOG_KEY;

@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {

    private static final Set<String> IMAGE_SUFFIXES = new HashSet<>(
            Arrays.asList("jpg", "jpeg", "png", "gif", "webp"));

    @Value("${citygo.upload-dir}")
    private String uploadDir;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }
        try {
            String suffix = imageSuffix(image.getOriginalFilename());
            if (suffix == null) {
                return Result.fail("只支持 jpg、png、gif、webp");
            }
            String fileName = createNewFileName(suffix);
            Path target = resolveInsideUploadDir(fileName);
            if (target == null) {
                return Result.fail("错误的文件名称");
            }
            image.transferTo(target.toFile());
            try {
                stringRedisTemplate.opsForSet().add(UPLOAD_BLOG_KEY + user.getId(), fileName);
            } catch (RuntimeException e) {
                FileUtil.del(target.toFile());
                throw e;
            }
            log.debug("文件上传成功，{}", fileName);
            return Result.ok(fileName);
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    @GetMapping("/blog/delete")
    public Result deleteBlogImg(@RequestParam("name") String filename) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }
        String relative = relativeUploadName(filename);
        if (relative == null || imageSuffix(relative) == null) {
            return Result.fail("错误的文件名称");
        }
        Boolean owned = stringRedisTemplate.opsForSet().isMember(UPLOAD_BLOG_KEY + user.getId(), relative);
        if (!Boolean.TRUE.equals(owned)) {
            return Result.fail("错误的文件名称");
        }
        Path target = resolveInsideUploadDir(relative);
        if (target == null) {
            return Result.fail("错误的文件名称");
        }
        File file = target.toFile();
        if (file.isDirectory() || !file.isFile()) {
            return Result.fail("错误的文件名称");
        }
        FileUtil.del(file);
        stringRedisTemplate.opsForSet().remove(UPLOAD_BLOG_KEY + user.getId(), relative);
        return Result.ok();
    }

    /**
     * 展示地址 /imgs/blogs/... 和相对路径 blogs/... 都收成上传目录里的相对路径。
     */
    private String relativeUploadName(String filename) {
        if (StrUtil.isBlank(filename) || filename.indexOf('\0') >= 0) {
            return null;
        }
        String name = filename.trim();
        if (name.startsWith("/imgs/")) {
            name = name.substring("/imgs/".length());
        } else if (name.startsWith("imgs/")) {
            name = name.substring("imgs/".length());
        } else if (name.startsWith("/")) {
            name = name.substring(1);
        }
        return name;
    }

    /**
     * 拼出来的路径必须还在上传目录里。请求里的 ../ 不能删到目录外面。
     */
    private Path resolveInsideUploadDir(String filename) {
        if (StrUtil.isBlank(filename) || filename.indexOf('\0') >= 0) {
            return null;
        }
        Path base = Paths.get(uploadDir).toAbsolutePath().normalize();
        Path target = base.resolve(filename).normalize();
        if (!target.startsWith(base)) {
            return null;
        }
        return target;
    }

    private String imageSuffix(String filename) {
        String suffix = FileUtil.extName(filename);
        if (StrUtil.isBlank(suffix)) {
            return null;
        }
        suffix = suffix.toLowerCase(Locale.ROOT);
        if (!IMAGE_SUFFIXES.contains(suffix)) {
            return null;
        }
        return suffix;
    }

    private String createNewFileName(String suffix) {
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        File dir = new File(uploadDir, StrUtil.format("blogs/{}/{}", d1, d2));
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return StrUtil.format("blogs/{}/{}/{}.{}", d1, d2, name, suffix);
    }
}
