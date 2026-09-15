package ssafy.a507.backend.domain.account.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import ssafy.a507.backend.domain.account.entity.UserChannel;

public final class UserDtos {

    private UserDtos() {}

    /**
     * 닉네임 확정·변경 요청. 온보딩과 프로필 수정이 같은 API 를 쓴다.
     *
     * <p>공백만 있는 값과 앞뒤 공백을 막는다 — 화면에서 구분되지 않는 닉네임이 유일성만 통과하는 것을 피한다.
     */
    public record NicknameRequest(
            @NotBlank
                    @Size(min = 2, max = 30)
                    @Pattern(regexp = "\\S(.*\\S)?", message = "닉네임 앞뒤에 공백을 둘 수 없습니다.")
                    String nickname) {}

    public record NicknameAvailability(boolean available) {}

    /**
     * 프로필 수정 요청 (명세 §회원 · ANT-AUTH-05).
     *
     * <p><b>네 필드가 모두 선택이다.</b> 보내지 않은 필드는 건드리지 않는다 — 온보딩은 닉네임만 보내고,
     * 소개만 고치는 화면은 소개만 보낸다. 한 요청이 전부를 실어야 한다면, 자기가 모르는 값까지 실어
     * 보내다 남의 값을 지우는 화면이 생긴다.
     *
     * <p><b>배열 둘은 "보냈으면 전체 교체"다.</b> 부분 수정(하나 추가·하나 삭제)을 두지 않은 것은 명세의
     * 결정이다 — 화면이 목록 전체를 들고 있으므로 그대로 덮는 편이 단순하고, 순서를 바꾸는 데 별도 API
     * 가 필요 없다. 빈 배열은 <b>비우겠다</b>는 뜻이라 null(건드리지 않음)과 다르다.
     *
     * <p>닉네임에 {@code @NotBlank} 를 걸지 않는다 — 여기서는 "안 보냄"이 정상이다. 대신 보냈다면
     * {@link NicknameRequest} 와 같은 규칙을 받는다.
     */
    public record ProfileUpdateRequest(
            @Size(min = 2, max = 30)
                    @Pattern(regexp = "\\S(.*\\S)?", message = "닉네임 앞뒤에 공백을 둘 수 없습니다.")
                    String nickname,
            @Size(max = 200) String introduce,
            List<@NotBlank @Size(max = 30) String> interests,
            List<@Valid ChannelRequest> channels) {}

    /**
     * 외부 채널 한 줄.
     *
     * <p>url 은 <b>https 만</b> 받는다(ERD · 명세 §회원). 남의 프로필에 걸리는 링크라 http 로 받으면
     * 중간에 가로채이고, {@code javascript:} 같은 것을 저장하면 클릭 한 번이 공격이 된다. 화면에서
     * 막는 것으로는 부족하다 — API 를 직접 부르면 그만이라 서버가 최종 판정자다.
     */
    public record ChannelRequest(
            @NotNull UserChannel.Platform platform,
            @NotBlank
                    @Size(max = 300)
                    @Pattern(regexp = "https://.+", message = "채널 주소는 https 로 시작해야 합니다.")
                    String url) {}

    /**
     * 세션 복원·프로필 화면이 쓰는 프로필.
     *
     * <p>온보딩 완료 여부는 따로 내려보내지 않는다 — 닉네임과 지갑 연동이 각각 어느 단계까지 왔는지
     * 이미 담겨 있고, 어느 단계를 막을지는 화면이 정한다.
     */
    public record MeResponse(
            Long id,
            String nickname,
            String introduce,
            /** 관심 섹터. 없으면 빈 배열이다 — null 을 내리면 화면마다 가드를 따로 써야 한다 */
            List<String> interests,
            /** 외부 채널. 등록 순(created_at)으로 내린다 */
            List<ChannelResponse> channels,
            String walletAddress,
            boolean walletLinked,
            String role) {}

    public record ChannelResponse(UserChannel.Platform platform, String url) {
        public static ChannelResponse from(UserChannel channel) {
            return new ChannelResponse(channel.getPlatform(), channel.getUrl());
        }
    }
}
