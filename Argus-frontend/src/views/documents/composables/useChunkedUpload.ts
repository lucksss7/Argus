import { reactive } from 'vue'
import {
  completeDocumentUpload,
  initDocumentUpload,
  uploadDocumentChunk,
} from '@/api/document'

/**
 * 分片上传阶段：
 * - ''：空闲
 * - hashing：计算文件整体 SHA-256 指纹（用于秒传/断点续传）
 * - init：初始化上传会话
 * - uploading：逐片上传
 * - completing：通知服务端合并分片
 */
export type ChunkedUploadStage = '' | 'hashing' | 'init' | 'uploading' | 'completing'

/** 单个分片大小：5MB（后端限制单分片最大 10MB，取 5MB 留出余量） */
const DEFAULT_CHUNK_SIZE = 5 * 1024 * 1024
/** 计算文件哈希时一次读入内存的字节数，用于展示哈希进度 */
const HASH_READ_SIZE = 8 * 1024 * 1024

/**
 * 大文件分片上传组合式函数。
 *
 * 对应后端接口：POST /documents/upload/init -> POST /documents/upload/chunks -> POST /documents/upload/{id}/complete。
 * 哈希统一使用小写十六进制 SHA-256（与后端 computeSha256 的 HexFormat 输出一致）。
 *
 * 返回一个 reactive 状态对象，调用方通过 `reactive(useChunkedUpload())` 保持同一份响应式引用。
 */
export function useChunkedUpload() {
  let abortController: AbortController | null = null

  const state = reactive({
    isUploading: false,
    /** 整体上传进度（0-100），覆盖分片上传阶段 */
    progress: 0,
    error: '',
    stage: '' as ChunkedUploadStage,
    /** 哈希阶段的进度文案，如 '42%' */
    hashingProgress: '',
    chunkProgress: {
      uploaded: 0,
      total: 0,
    },
    reset,
    cancel,
    uploadFile,
  })

  function reset() {
    abortController?.abort()
    abortController = null
    state.isUploading = false
    state.progress = 0
    state.error = ''
    state.stage = ''
    state.hashingProgress = ''
    state.chunkProgress.uploaded = 0
    state.chunkProgress.total = 0
  }

  function cancel() {
    abortController?.abort()
  }

  /**
   * 上传单个文件（分片路径）。
   *
   * @param file 待上传文件
   * @param groupId 目标群组 ID
   * @throws 上传失败（包括取消）时抛出异常
   */
  async function uploadFile(file: File, groupId: number): Promise<void> {
    reset()
    state.isUploading = true
    abortController = new AbortController()
    const signal = abortController.signal

    try {
      // 1. 计算整个文件的 SHA-256 指纹
      state.stage = 'hashing'
      const fileHash = await hashFile(file, (pct) => {
        state.hashingProgress = pct
      })
      state.hashingProgress = ''

      // 2. 初始化上传会话（服务端据此判断秒传或断点续传）
      state.stage = 'init'
      const chunkCount = Math.ceil(file.size / DEFAULT_CHUNK_SIZE)
      const initResult = await initDocumentUpload(
        {
          groupId,
          fileName: file.name,
          fileSize: file.size,
          contentType: file.type || 'application/octet-stream',
          fileHash,
          chunkSize: DEFAULT_CHUNK_SIZE,
          chunkCount,
        },
        signal,
      )

      if (initResult.instantUpload) {
        state.progress = 100
        return
      }

      const uploadId = initResult.uploadId
      if (!uploadId) {
        throw new Error('初始化上传失败：未返回上传会话 ID')
      }

      // 3. 逐片上传（跳过服务端已有的分片，支持断点续传）
      state.stage = 'uploading'
      state.chunkProgress.total = chunkCount
      const uploadedSet = new Set<number>(initResult.uploadedChunks ?? [])
      state.chunkProgress.uploaded = uploadedSet.size

      for (let i = 0; i < chunkCount; i += 1) {
        throwIfAborted(signal)
        if (uploadedSet.has(i)) continue

        const start = i * DEFAULT_CHUNK_SIZE
        const end = Math.min(start + DEFAULT_CHUNK_SIZE, file.size)
        const chunk = file.slice(start, end)
        const chunkHash = await sha256Hex(await chunk.arrayBuffer())

        await uploadDocumentChunk(
          { uploadId, chunkIndex: i, chunkHash, chunk },
          () => {},
          signal,
        )

        state.chunkProgress.uploaded += 1
        state.progress = Math.round((state.chunkProgress.uploaded / chunkCount) * 100)
      }

      // 4. 通知服务端合并分片
      state.stage = 'completing'
      await completeDocumentUpload(uploadId, signal)
      state.progress = 100
    } catch (err) {
      if (!signal.aborted) {
        state.error = err instanceof Error ? err.message : '上传失败'
      }
      throw err
    } finally {
      state.isUploading = false
      state.stage = ''
      abortController = null
    }
  }

  return state
}

/** 计算字节数组的小写十六进制 SHA-256 */
async function sha256Hex(data: ArrayBuffer): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', data)
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('')
}

/** 分块读取文件并计算整体 SHA-256，同时回报进度 */
async function hashFile(file: File, onProgress: (pct: string) => void): Promise<string> {
  const total = file.size
  const parts: Uint8Array[] = []
  let offset = 0

  while (offset < total) {
    const end = Math.min(offset + HASH_READ_SIZE, total)
    parts.push(new Uint8Array(await file.slice(offset, end).arrayBuffer()))
    offset = end
    onProgress(`${Math.round((offset / total) * 100)}%`)
  }

  const full = new Uint8Array(total)
  let cursor = 0
  for (const part of parts) {
    full.set(part, cursor)
    cursor += part.length
  }

  return sha256Hex(full.buffer)
}

function throwIfAborted(signal: AbortSignal) {
  if (signal.aborted) {
    throw new Error('上传已取消')
  }
}
