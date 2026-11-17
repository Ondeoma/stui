// 画像を中央で正方形に切り取り、size px に縮める（アイコンの登録用）。GIF などで読めないときは元のファイルを返す。
export function squareThumb(file, size = 256) {
  return new Promise((resolve) => {
    const url = URL.createObjectURL(file)
    const img = new Image()
    img.onload = () => {
      const s = Math.min(img.naturalWidth, img.naturalHeight)
      const canvas = document.createElement('canvas')
      canvas.width = canvas.height = Math.min(size, s)
      canvas.getContext('2d').drawImage(img, (img.naturalWidth - s) / 2, (img.naturalHeight - s) / 2, s, s, 0, 0, canvas.width, canvas.height)
      URL.revokeObjectURL(url)
      canvas.toBlob((b) => resolve(b ? new File([b], 'avatar.png', { type: 'image/png' }) : file), 'image/png')
    }
    img.onerror = () => { URL.revokeObjectURL(url); resolve(file) }
    img.src = url
  })
}
