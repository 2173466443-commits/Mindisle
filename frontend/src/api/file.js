import http from './http'

// 后端 FileController：单张图片上传（魔数白名单 + 重编码去 EXIF，只认 jpg/png/gif）。
// 这里必须自己拼 FormData 走 axios 而不是用 <el-upload action="...">：
// 只有走 http 实例才带得上 Authorization，否则一律 401/10002。
export const uploadImage = (file) => {
  const form = new FormData()
  form.append('file', file)
  return http.post('/files/image', form, { silent: true })
}
