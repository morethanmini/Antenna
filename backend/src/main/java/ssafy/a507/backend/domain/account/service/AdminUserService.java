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
import ssafy.a507.backend.domain.auth.service.RefreshTokenStore;

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

    /**
     * 한 쪽 인원. 뒤가 더 있으면 응답의 {@code nextBefore} 로 알리고, 화면은 그 값을 {@code before} 로
     * 다시 부른다(id 커서). 회원이 100명을 넘자 상한만으로는 앞쪽 절반이 검색 말고는 안 보였다(2026-09-22 QA).
     */
    private static final int LIST_LIMIT = 50;

    /** 통합 검색(SearchService)과 같은 값이다. 닉네임이 30자라 이보다 긴 검색어는 맞을 것이 없다. */
    private static final int MAX_QUERY_LENGTH = 50;

    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;
    private final RefreshTokenStore refreshTokenStore;

    /**
     * 닉네임으로 회원을 찾는다. {@code q} 가 비면 최근 가입 순으로 앞에서 {@value #LIST_LIMIT} 명.
     *
     * <p>AC 에는 없는 엔드포인트다. 제재 화면이 대상을 고를 길이 없으면 API 만 있고 쓸 수 없어서 넣었다.
     *
     * <p>검색어가 없어도 <b>널이 아니라 빈 문자열</b>을 넘긴다 — 리포지토리 주석 참고(널이면 PostgreSQL 에서 깨진다).
     */
    @Transactional(readOnly = true)
    public AdminUserListResponse search(String q, Long before) {
        requireAdmin();
        String keyword = q == null ? "" : q.trim();
        if (keyword.length() > MAX_QUERY_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "q");
        }
        // 한 명 더 받아 "뒤가 있나" 를 안다 — count 쿼리를 따로 날리지 않는다.
        List<User> found =
                userRepository.searchForAdmin(
                        escapeLike(keyword), before == null ? Long.MAX_VALUE : before, Limit.of(LIST_LIMIT + 1));
        List<AdminUserItem> items = found.stream().limit(LIST_LIMIT).map(AdminUserItem::of).toList();
        Long nextBefore = found.size() > LIST_LIMIT ? items.get(items.size() - 1).id() : null;
        return new AdminUserListResponse(items, nextBefore);
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
        /* 다른 관리자도 못 내린다. 관리자끼리 서로 막으면 남는 길이 DB 뿐이다(2026-09-22 QA). */
        if (target.getRole() == User.Role.ADMIN) {
            throw new BusinessException(ErrorCode.ADMIN_TARGET_ADMIN);
        }
        User.Status next = User.Status.valueOf(request.status().name());
        target.changeStatus(next);
        /* 차단과 동시에 refresh 를 지운다. 안 지우면 access 만료(30분)까지 그대로 쓰고,
           refresh 는 다음 갱신(AuthService.refresh)에서야 거부된다(2026-09-22 QA). */
        if (next == User.Status.BANNED) {
            refreshTokenStore.revokeAll(userId);
        }
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

    /**
     * LIKE 와일드카드를 벗어난다 — 검색어에 든 {@code %}·{@code _} 는 글자 그대로 찾는다.
     *
     * <p>안 하면 {@code %} 한 글자가 전 회원을 부르고 {@code _} 가 아무 글자나 맞는다
     * (2026-09-22 QA). 역슬래시를 먼저 바꾸지 않으면 뒤에 붙인 이스케이프 문자까지 다시
     * 벗어나게 되어 순서가 중요하다.
     */
    private static String escapeLike(String keyword) {
        return keyword
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
