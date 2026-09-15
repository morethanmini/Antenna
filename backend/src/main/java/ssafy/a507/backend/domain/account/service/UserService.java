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

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository users;
    private final UserInterestRepository interests;
    private final UserChannelRepository channels;

    @Transactional(readOnly = true)
    public MeResponse me(Long userId) {
        return toResponse(find(userId));
    }

    @Transactional(readOnly = true)
    public boolean isNicknameAvailable(String nickname) {
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
                        .map(request -> UserChannel.of(user, request.platform(), request.url()))
                        .toList());
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
