package ssafy.a507.backend.domain.account.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.common.security.CurrentUserProvider;
import ssafy.a507.backend.domain.account.dto.AdminUserDtos.AdminUserItem;
import ssafy.a507.backend.domain.account.dto.AdminUserDtos.AdminUserListResponse;
import ssafy.a507.backend.domain.account.dto.AdminUserDtos.UserStatusUpdateRequest;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.account.repository.UserRepository;

/**
 * 관리자 회원 제재 (ANT-ADMIN-01).
 *
 * <p>차단은 상태만 바꾼다 — 로그인 거부는 {@code AuthService} 가 이미 하고 있고
 * ({@code Status.BANNED → ACCOUNT_BANNED}), 기존 예측·원장은 그대로 남는다.
 *
 * <p>권한 검사는 {@code AdminModerationService} 와 같은 방식으로 여기 안에 둔다.
 */
@Service
@RequiredArgsConstructor
public class AdminUserService {

    /** 한 번에 돌려주는 최대 인원. 운영자가 눈으로 훑는 목록이라 페이지네이션 대신 상한만 둔다. */
    private static final int LIST_LIMIT = 50;

    /** 통합 검색(SearchService)과 같은 값이다. 닉네임이 30자라 이보다 긴 검색어는 맞을 것이 없다. */
    private static final int MAX_QUERY_LENGTH = 50;

    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;

    /**
     * 닉네임으로 회원을 찾는다. {@code q} 가 비면 최근 가입 순으로 앞에서 {@value #LIST_LIMIT} 명.
     *
     * <p>AC 에는 없는 엔드포인트다. 제재 화면이 대상을 고를 길이 없으면 API 만 있고 쓸 수 없어서 넣었다.
     *
     * <p>검색어가 없어도 <b>널이 아니라 빈 문자열</b>을 넘긴다 — 리포지토리 주석 참고(널이면 PostgreSQL 에서 깨진다).
     */
    @Transactional(readOnly = true)
    public AdminUserListResponse search(String q) {
        requireAdmin();
        String keyword = q == null ? "" : q.trim();
        if (keyword.length() > MAX_QUERY_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "q");
        }
        List<AdminUserItem> items =
                userRepository.searchForAdmin(keyword, Limit.of(LIST_LIMIT)).stream()
                        .map(AdminUserItem::of)
                        .toList();
        return new AdminUserListResponse(items);
    }

    @Transactional
    public void changeStatus(Long userId, UserStatusUpdateRequest request) {
        User admin = requireAdmin();

        /* 자기 자신은 못 내린다. 내리면 AuthService 가 로그인을 막아 되돌릴 길이 없고,
           DB 를 직접 고치는 수밖에 없어진다. */
        if (admin.getId().equals(userId)) {
            throw new BusinessException(ErrorCode.ADMIN_SELF_DEMOTE);
        }

        User target = userRepository
                .findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        target.changeStatus(User.Status.valueOf(request.status().name()));
    }

    private User requireAdmin() {
        User user = userRepository
                .findById(currentUserProvider.currentUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        if (user.getRole() != User.Role.ADMIN) {
            throw new BusinessException(ErrorCode.ADMIN_ONLY);
        }
        return user;
    }
}
