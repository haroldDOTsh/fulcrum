{{- define "fulcrum.name" -}}
fulcrum
{{- end -}}

{{- define "fulcrum.labels" -}}
app.kubernetes.io/name: {{ include "fulcrum.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: Helm
sh.harold.fulcrum/profile: {{ .Values.profile | quote }}
sh.harold.fulcrum/semantic-model: {{ .Values.semanticModel | quote }}
{{- end -}}
