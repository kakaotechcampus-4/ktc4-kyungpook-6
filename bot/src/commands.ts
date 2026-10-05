/**
 * 슬래시 명령어 정의. `npm run register` 가 이 목록을 Discord 에 올린다.
 *
 * 명령어 이름에 한글을 쓸 수 있다(Discord 가 허용한다). 팀이 한국어로 일하니 그대로 둔다.
 */

export const COMMANDS = [
    {
        name: "예약",
        description: "지정한 시각에 이 채널로 메시지를 보냅니다 (예약 사실은 본인만 보입니다)",
        options: [
            {
                name: "시각",
                description: "30분후 · 09:00 · 내일 09:00 · 10-02 09:00 · 2026-10-02 09:00",
                type: 3, // STRING
                required: true,
            },
            {
                name: "내용",
                description: "보낼 메시지",
                type: 3,
                required: true,
            },
        ],
    },
    {
        name: "예약목록",
        description: "내가 건 예약을 봅니다",
    },
    {
        name: "예약취소",
        description: "내가 건 예약을 취소합니다",
        options: [
            {
                name: "번호",
                description: "/예약목록 에 나온 번호",
                type: 4, // INTEGER
                required: true,
            },
        ],
    },
    {
        name: "pr상태",
        description: "열린 PR 을 요약해 봅니다",
    },
] as const;
