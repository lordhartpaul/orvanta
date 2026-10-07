{{- define "orvanta.name" -}}
{{- .Chart.Name -}}
{{- end -}}

{{- define "orvanta.fullname" -}}
{{- if contains .Chart.Name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "orvanta.labels" -}}
app.kubernetes.io/name: {{ include "orvanta.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}

{{- define "orvanta.image" -}}
{{- printf "%s:%s" .Values.image.repository (default .Chart.AppVersion .Values.image.tag) -}}
{{- end -}}

{{/* what both deployments share: the pod security context, the config map and the secret, the data volume */}}
{{- define "orvanta.envFrom" -}}
envFrom:
  - configMapRef: {name: {{ include "orvanta.fullname" . }}-config}
  - secretRef: {name: {{ .Values.existingSecret }}}
{{- end -}}

{{- define "orvanta.volumeMounts" -}}
{{- if .Values.dataVolume.enabled }}
- {name: data, mountPath: /app/data}
{{- end }}
{{- if .Values.schemasConfigMap }}
- {name: schemas, mountPath: /app/schemas/iso20022, readOnly: true}
{{- end }}
{{- end -}}

{{- define "orvanta.volumes" -}}
{{- if .Values.dataVolume.enabled }}
- name: data
  persistentVolumeClaim: {claimName: {{ include "orvanta.fullname" . }}-data}
{{- end }}
{{- if .Values.schemasConfigMap }}
- name: schemas
  configMap: {name: {{ .Values.schemasConfigMap }}}
{{- end }}
{{- end -}}
