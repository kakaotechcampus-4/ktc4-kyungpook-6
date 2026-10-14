import { useEffect, useState } from "react";

/**
 * 고른 사진 파일을 <img src> 로 미리 보여 줄 주소. 파일이 바뀌거나 화면이 사라지면 주소를 풀어 메모리를 돌려준다.
 * 파일이 없으면 null.
 */
export const useObjectUrl = (file: File | null) => {
  const [preview, setPreview] = useState<{ file: File; url: string } | null>(null);

  useEffect(() => {
    if (!file) return;
    const url = URL.createObjectURL(file);
    /*
      주소는 effect 안에서 만들어야 정리(revoke)와 짝이 맞는다. 렌더 중에 만들면 StrictMode 가 effect 를
      한 번 정리했다 다시 돌릴 때 이미 풀린 주소가 남아 사진이 깨진다.
    */
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setPreview({ file, url });
    return () => URL.revokeObjectURL(url);
  }, [file]);

  // 파일이 바뀐 직후 한 번은 지난 파일의 주소가 남아 있으니 같은 파일일 때만 쓴다.
  return preview?.file === file ? preview.url : null;
};
