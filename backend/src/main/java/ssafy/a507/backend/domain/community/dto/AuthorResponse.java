package ssafy.a507.backend.domain.community.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import ssafy.a507.backend.domain.account.entity.User;

/**
 * 글·리포트·댓글 작성자.
 *
 * <p>{@code avatarUrl} 은 <b>필요한 화면만 채운다.</b> {@link #from(User)} 는 null 을 넣고,
 * 사진을 함께 그리는 목록만 {@link #of(User, String)} 로 값을 실어 보낸다 — 프로필 사진은
 * {@code users.avatar_file_id} 를 {@code upload_files.url} 로 한 번 바꿔야 나오는 값이라
 * 정적 팩터리가 혼자 만들어 낼 수 없고, 목록마다 한 번에 모아 읽어야 N+1 이 없다
 * ({@code AvatarUrls.byUserId}).
 *
 * <p>{@code NON_NULL} 을 레코드에 건 이유 — 채우지 않는 13곳의 응답에 {@code "avatarUrl": null}
 * 이 새로 끼면, 이 필드를 쓰지도 않는 화면들의 계약이 바뀐다. 안 채우면 키가 아예 없다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthorResponse(Long userId, String nickname, String avatarUrl) {

    /** 사진이 필요 없는 자리. 지금까지의 모든 호출부가 여기로 온다 */
    public static AuthorResponse from(User user) {
        return new AuthorResponse(user.getId(), user.getNickname(), null);
    }

    /** 사진까지 그리는 목록. {@code avatarUrl} 은 안 올렸거나 파일이 사라졌으면 null 이다 */
    public static AuthorResponse of(User user, String avatarUrl) {
        return new AuthorResponse(user.getId(), user.getNickname(), avatarUrl);
    }
}
