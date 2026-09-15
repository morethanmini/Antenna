package ssafy.a507.backend.domain.account.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.upload.entity.UploadFile;
import ssafy.a507.backend.domain.upload.repository.UploadFileRepository;

/**
 * 프로필 사진 주소를 낸다. {@code users.avatar_file_id} 는 업로드 파일의 id 라, 화면에 내리려면
 * {@code upload_files.url} 로 한 번 바꿔야 한다.
 *
 * <p><b>목록용 메서드를 따로 두는 이유는 N+1 이다.</b> 랭킹 한 쪽이 50 줄이면 한 줄씩 찾을 때
 * 쿼리가 50 번 나간다. {@link #byUserId(Collection)} 은 id 를 모아 한 번에 읽는다.
 *
 * <p>파일이 지워졌으면 사진이 없는 것으로 본다 — 목록이 깨지는 것보다 기본 그림이 낫다.
 * {@code users.avatar_file_id} 에 FK 를 걸지 않은 것과 같은 판단이다(User.avatarFileId).
 *
 * <p>소유자 조건을 걸지 않는다. 여기 오는 id 는 사용자가 보낸 값이 아니라 우리가 users 에 저장해
 * 둔 값이라, 이미 {@code UserService.applyAvatar} 가 "내 파일인지" 를 확인하고 넣은 것이다.
 */
@Component
@RequiredArgsConstructor
public class AvatarUrls {

    private final UploadFileRepository uploads;

    /** 한 사람. 사진이 없거나 파일이 사라졌으면 null 이다 */
    public String of(User user) {
        if (user == null || user.getAvatarFileId() == null) {
            return null;
        }
        return uploads.findById(user.getAvatarFileId()).map(UploadFile::getUrl).orElse(null);
    }

    /**
     * 여러 사람을 한 번에. 돌려주는 표에는 <b>사진이 있는 사람만</b> 담긴다 —
     * 없는 사람은 키 자체가 없으므로 부르는 쪽에서 {@code map.get(id)} 가 null 이 된다.
     */
    public Map<Long, String> byUserId(Collection<User> users) {
        Set<String> fileIds = users.stream()
                .map(User::getAvatarFileId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (fileIds.isEmpty()) {
            return Map.of();
        }
        List<UploadFile> files = uploads.findAllById(fileIds);
        Map<String, String> urlByFileId = files.stream()
                .collect(Collectors.toMap(UploadFile::getId, UploadFile::getUrl));

        Map<Long, String> out = new HashMap<>();
        for (User user : users) {
            String url = user.getAvatarFileId() == null ? null : urlByFileId.get(user.getAvatarFileId());
            if (url != null) {
                out.put(user.getId(), url);
            }
        }
        return out;
    }
}
