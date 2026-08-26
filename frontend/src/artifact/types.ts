export type ArtifactType =
  | 'QUERY_RESULT'
  | 'ATTRIBUTION_RESULT'
  | 'CHART'
  | 'RANKED_RESULT'
  | 'DERIVED_RESULT'
  | 'FILE'

export type ArtifactView<T = unknown> = {
  artifactId: string
  artifactType: ArtifactType
  schemaVersion: number
  status: 'PENDING' | 'COMPLETED' | 'FAILED'
  title: string | null
  ownerUserId: string
  conversationId: string
  sourceMessageId: string | null
  createdBy: string
  storageType: 'INLINE' | 'FILE' | 'OBJECT_STORAGE'
  rowCount: number | null
  createdAt: string
  expiresAt: string | null
  payload: T
}

export type QueryArtifactPayload = {
  summary: string
  columns: {
    id: string
    displayName: string
    role: 'DIMENSION' | 'METRIC'
    dataType: 'STRING' | 'NUMBER' | 'DATE' | 'DATETIME' | 'BOOLEAN'
    unit: string | null
  }[]
  rows: Record<string, unknown>[]
  totalRows: number
  truncated: boolean
}

export type ChartArtifactPayload = {
  sourceArtifactId: string
  chartType: 'AUTO' | 'LINE' | 'BAR' | 'PIE'
  dimensionId: string
  dimensionDisplayName: string
  categories: string[]
  series: {
    metricId: string
    displayName: string
    unit: string | null
    values: (number | null)[]
  }[]
}

export type AttributionArtifactPayload = {
  metricId: string
  metricName: string
  currentPeriod: string
  comparisonPeriod: string
  overall: {
    currentValue: number | null
    comparisonValue: number | null
    changeAmount: number | null
    changeRate: number | null
    direction: string | null
  } | null
  summary: string
  findings: string[]
  recommendations: string[]
}

export type FileArtifactPayload = {
  sourceArtifactId: string
  fileName: string
  format: 'CSV' | 'XLSX'
  mediaType: string
  sizeBytes: number
  fileChecksum: string
}
