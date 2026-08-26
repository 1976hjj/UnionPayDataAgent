import type { ArtifactView } from './types'

export async function getArtifact(userId: string, artifactId: string): Promise<ArtifactView> {
  const response = await fetch(
    `/api/artifacts/${encodeURIComponent(artifactId)}?userId=${encodeURIComponent(userId)}`,
  )
  if (!response.ok) throw new Error('读取分析产物失败')
  return response.json() as Promise<ArtifactView>
}

export async function getArtifacts(userId: string, artifactIds: string[]): Promise<ArtifactView[]> {
  const uniqueIds = [...new Set(artifactIds.filter(Boolean))]
  const settled = await Promise.allSettled(uniqueIds.map((id) => getArtifact(userId, id)))
  return settled.flatMap((item) => item.status === 'fulfilled' ? [item.value] : [])
}

export async function getConversationArtifacts(
  userId: string,
  conversationId: string,
): Promise<ArtifactView[]> {
  const response = await fetch(
    `/api/artifacts/conversations/${encodeURIComponent(conversationId)}?userId=${encodeURIComponent(userId)}`,
  )
  if (!response.ok) return []
  const summaries = await response.json() as ArtifactView[]
  return getArtifacts(userId, summaries.map((artifact) => artifact.artifactId))
}

export function artifactDownloadUrl(userId: string, artifactId: string) {
  return `/api/artifacts/${encodeURIComponent(artifactId)}/download?userId=${encodeURIComponent(userId)}`
}
