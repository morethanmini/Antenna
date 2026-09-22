package ssafy.a507.backend.domain.account.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.domain.account.dto.UserDtos.ChannelRequest;
import ssafy.a507.backend.domain.account.dto.UserDtos.ChannelResponse;
import ssafy.a507.backend.domain.account.dto.UserDtos.MeResponse;
import ssafy.a507.backend.domain.account.dto.UserDtos.ProfileUpdateRequest;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.account.entity.UserChannel;
import ssafy.a507.backend.domain.account.entity.UserInterest;
import ssafy.a507.backend.domain.account.repository.UserChannelRepository;
import ssafy.a507.backend.domain.account.repository.UserInterestRepository;
import ssafy.a507.backend.domain.account.repository.UserRepository;
import ssafy.a507.backend.domain.upload.entity.UploadFile;
import ssafy.a507.backend.domain.upload.repository.UploadFileRepository;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository users;
    private final UserInterestRepository interests;
    private final UserChannelRepository channels;
    private final UploadFileRepository uploads;

    @Transactional(readOnly = true)
    public MeResponse me(Long userId) {
        return toResponse(find(userId));
    }

    /**
     * 이 닉네임을 쓸 수 있는가 — <b>내 현재 닉네임은 쓸 수 있는 것으로 친다.</b>
     *
     * <p>자기 자신을 제외하지 않으면 "내 이름 그대로 두기" 가 막힌다. 온보딩 닉네임 화면은 이 응답으로
     * 버튼을 여는데, 온보딩을 마친 계정이 다시 들어와 자기 닉네임을 넣으면 "이미 사용 중" 으로 잠겨
     * 진행이 안 됐다(2026-09-22 QA). 저장 경로({@code applyNickname})는 이미 같은 값을 통과시키므로
     * 검사와 저장이 서로 다른 말을 하고 있었다 — 저장 쪽에 맞춘다.
     */
    @Transactional(readOnly = true)
    public boolean isNicknameAvailable(Long userId, String nickname) {
        if (nickname.equals(find(userId).getNickname())) {
            return true;
        }
        return !users.existsByNickname(nickname);
    }

    /**
     * 프로필 수정 (명세 §회원 · ANT-AUTH-05). 온보딩의 닉네임 확정도 이 경로를 쓴다 — 닉네임만 실어 보낸다.
     *
     * <p>보내지 않은(null) 필드는 건드리지 않는다. 배열 둘은 보냈으면 전체 교체이고, 빈 배열은 비우겠다는
     * 뜻이다. 넷을 한 트랜잭션에 묶은 이유는, 닉네임이 409 로 막혔는데 관심 섹터만 바뀌어 있는 상태를
     * 만들지 않기 위해서다 — 화면은 한 번의 "저장"으로 받아들인다.
     */
    @Transactional
    public MeResponse updateProfile(Long userId, ProfileUpdateRequest request) {
        User user = find(userId);

        if (request.nickname() != null) {
            applyNickname(user, request.nickname());
        }
        if (request.introduce() != null) {
            user.changeIntroduce(request.introduce());
        }
        if (request.interests() != null) {
            replaceInterests(user, request.interests());
        }
        if (request.channels() != null) {
            replaceChannels(user, request.channels());
        }
        if (request.avatarFileId() != null) {
            applyAvatar(user, request.avatarFileId());
        }
        return toResponse(user);
    }

    private void applyNickname(User user, String nickname) {
        if (nickname.equals(user.getNickname())) {
            return;
        }
        if (users.existsByNickname(nickname)) {
            throw new BusinessException(ErrorCode.DUPLICATE_NICKNAME);
        }
        user.changeNickname(nickname);
        try {
            users.flush();
        } catch (DataIntegrityViolationException e) {
            // 위 검사와 flush 사이에 같은 닉네임이 들어온 경우. UQ 가 최종 판정자다.
            throw new BusinessException(ErrorCode.DUPLICATE_NICKNAME);
        }
    }

    /**
     * 전체 교체. 지우고 다시 넣는다.
     *
     * <p>같은 값이 두 번 오면 UQ(user_id, sector) 에 걸린다. 400 으로 돌려주는 것보다 조용히 접는 편이
     * 맞다 — 사용자가 고칠 수 있는 잘못이 아니라 화면이 중복을 걸러야 할 일이고, 결과는 어차피 같다.
     * {@link LinkedHashSet} 이라 화면이 담은 순서는 지켜진다.
     *
     * <p>delete 를 먼저 flush 하지 않으면 Hibernate 가 insert 를 앞세워 UQ 에 걸린다. 지운 행과 같은
     * 값을 다시 넣는 것이 이 API 의 정상 동작이라 순서를 못 박아 둔다.
     */
    private void replaceInterests(User user, List<String> sectors) {
        interests.deleteByUserId(user.getId());
        interests.flush();

        Set<String> unique = new LinkedHashSet<>(sectors.stream().map(String::strip).toList());
        interests.saveAll(unique.stream().map(sector -> UserInterest.of(user, sector)).toList());
    }

    /** 같은 판단이다 — UQ 는 (user_id, url) 이라 url 로 중복을 접는다. */
    private void replaceChannels(User user, List<ChannelRequest> requests) {
        channels.deleteByUserId(user.getId());
        channels.flush();

        Set<String> seen = new LinkedHashSet<>();
        channels.saveAll(
                requests.stream()
                        .filter(request -> seen.add(request.url()))
                        .map(request -> UserChannel.of(user, request.platform(), request.label(), request.url()))
                        .toList());
    }

    /**
     * 프로필 사진을 건다. 빈 문자열이면 지운다.
     *
     * <p><b>내가 올린 PROFILE 파일만</b> 받는다. 소유자 조건이 없으면 남이 올린 id 를 알아내
     * 자기 프로필로 걸 수 있고(UploadFileRepository 머리말과 같은 이유), 용도 조건이 없으면
     * 광고 배너로 올린 16:9 이미지가 동그란 프로필 자리에 들어간다.
     */
    private void applyAvatar(User user, String fileId) {
        if (fileId.isBlank()) {
            user.changeAvatar(null);
            return;
        }
        UploadFile file = uploads
                .findByIdAndUserId(fileId, user.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST));
        if (file.getPurpose() != UploadFile.Purpose.PROFILE) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        user.changeAvatar(file.getId());
    }

    /**
     * 파일이 지워졌으면 사진이 없는 것으로 본다 — 회원 조회가 깨지는 것보다 기본 그림이 낫다.
     * users.avatar_file_id 에 FK 를 걸지 않은 것과 같은 판단이다(User.avatarFileId).
     */
    private String avatarUrl(User user) {
        if (user.getAvatarFileId() == null) {
            return null;
        }
        return uploads.findById(user.getAvatarFileId()).map(UploadFile::getUrl).orElse(null);
    }

    private User find(Long userId) {
        return users.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    private MeResponse toResponse(User user) {
        return new MeResponse(
                user.getId(),
                user.getNickname(),
                user.getIntroduce(),
                avatarUrl(user),
                interests.findByUserIdOrderByIdAsc(user.getId()).stream()
                        .map(UserInterest::getSector)
                        .toList(),
                channels.findByUserIdOrderByIdAsc(user.getId()).stream()
                        .map(ChannelResponse::from)
                        .toList(),
                user.getWalletAddress(),
                user.getWalletAddress() != null,
                user.getRole().name());
    }
}
