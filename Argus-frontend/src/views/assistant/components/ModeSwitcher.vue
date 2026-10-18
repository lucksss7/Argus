<script setup lang="ts">
import { computed } from 'vue'
import { useAuthStore } from '@/stores/auth'
import type { AssistantToolMode } from '@/types/assistant'

const props = defineProps<{
  mode: AssistantToolMode
  disabled?: boolean
  compact?: boolean
}>()

const emit = defineEmits<{
  'update:mode': [mode: AssistantToolMode]
}>()

const authStore = useAuthStore()

interface ModeOption {
  value: AssistantToolMode
  label: string
  icon: string
}

const ALL_MODES: ModeOption[] = [
  {
    value: 'CHAT',
    label: '对话',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" /></svg>',
  },
  {
    value: 'KB_SEARCH',
    label: '知识库检索',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="7" /><path d="M19 19L16 16" /><path d="M7 11h8" /></svg>',
  },
  {
    value: 'TICKET_QUERY',
    label: '工单查询',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="5" y="4" width="14" height="17" rx="2" /><path d="M9 4.5V3.5C9 2.95 9.45 2.5 10 2.5h4c.55 0 1 .45 1 1v1" /><path d="M8.5 12l2 2 5-5" /></svg>',
  },
  {
    value: 'TICKET_ASSIST',
    label: '工单助手',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="5" y="4" width="14" height="17" rx="2" /><path d="M12 9v6" /><path d="M9 12h6" /></svg>',
  },
]

// 后端仅对 TICKET_QUERY / TICKET_ASSIST 放行系统管理员，CHAT / KB_SEARCH 会拒绝管理员。
// 因此管理员只显示「工单查询」「工单助手」，普通用户四种模式全显示。
// 这里仅是前端显隐控制，最终仍由后端校验权限。
const modes = computed<ModeOption[]>(() =>
  authStore.isAdmin
    ? ALL_MODES.filter((m) => m.value === 'TICKET_QUERY' || m.value === 'TICKET_ASSIST')
    : ALL_MODES,
)

const activeIndex = computed(() => {
  const idx = modes.value.findIndex((m) => m.value === props.mode)
  return idx < 0 ? 0 : idx
})

function set(m: AssistantToolMode) {
  if (props.disabled) return
  if (m === props.mode) return
  emit('update:mode', m)
}
</script>

<template>
  <div
    class="mode-switcher"
    :class="{ 'is-compact': compact, 'is-disabled': disabled, 'is-single': modes.length === 1 }"
  >
    <span
      class="mode-switcher__slider"
      :class="`mode-switcher__slider--${mode.toLowerCase()}`"
      :style="{
        width: `calc(${100 / modes.length}% - 3px)`,
        transform: `translateX(${activeIndex * 100}%)`,
      }"
    />

    <button
      v-for="m in modes"
      :key="m.value"
      type="button"
      class="mode-switcher__btn"
      :class="{ 'is-active': m.value === mode }"
      @click="set(m.value)"
    >
      <span class="mode-switcher__icon" v-html="m.icon" />
      <span class="mode-switcher__label">{{ m.label }}</span>
    </button>
  </div>
</template>

<style scoped>
.mode-switcher {
  position: relative;
  display: inline-flex;
  align-items: center;
  padding: 3px;
  background: var(--surface-subtle);
  border: 1px solid var(--border-default);
  border-radius: 100px;
  user-select: none;
}

.mode-switcher.is-disabled {
  opacity: 0.6;
  pointer-events: none;
}

.mode-switcher__slider {
  position: absolute;
  top: 3px;
  bottom: 3px;
  left: 3px;
  border-radius: 100px;
  transition: transform 0.3s cubic-bezier(0.16, 1, 0.3, 1), background 0.3s ease, box-shadow 0.3s ease;
}

.mode-switcher__slider--chat {
  background: linear-gradient(135deg, var(--brand-primary), var(--brand-primary-dark));
  box-shadow:
    0 3px 10px rgba(74, 144, 217, 0.25),
    inset 0 1px 0 rgba(255, 255, 255, 0.15);
}

.mode-switcher__slider--kb_search {
  background: linear-gradient(135deg, var(--brand-accent), var(--brand-accent-dark));
  box-shadow:
    0 3px 10px rgba(92, 201, 193, 0.3),
    inset 0 1px 0 rgba(255, 255, 255, 0.15);
}

.mode-switcher__slider--ticket_query {
  background: linear-gradient(135deg, #f59e0b, #d97706);
  box-shadow:
    0 3px 10px rgba(245, 158, 11, 0.3),
    inset 0 1px 0 rgba(255, 255, 255, 0.15);
}

.mode-switcher__slider--ticket_assist {
  background: linear-gradient(135deg, #10b981, #059669);
  box-shadow:
    0 3px 10px rgba(16, 185, 129, 0.3),
    inset 0 1px 0 rgba(255, 255, 255, 0.15);
}

.mode-switcher__btn {
  position: relative;
  z-index: 1;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 16px;
  font-family: inherit;
  font-size: 0.8rem;
  font-weight: 600;
  letter-spacing: 0.01em;
  color: var(--text-muted);
  background: transparent;
  border: none;
  border-radius: 100px;
  cursor: pointer;
  transition: color 0.25s ease;
  min-width: 118px;
  justify-content: center;
  white-space: nowrap;
}

.mode-switcher__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.mode-switcher__btn svg {
  width: 13px;
  height: 13px;
}

.mode-switcher__btn:hover:not(.is-active) {
  color: var(--text-primary);
}

.mode-switcher__btn.is-active {
  color: #fff;
  font-weight: 700;
}

.mode-switcher.is-compact .mode-switcher__btn {
  padding: 5px 12px;
  min-width: 0;
  font-size: 0.74rem;
}

.mode-switcher.is-compact .mode-switcher__btn svg {
  width: 12px;
  height: 12px;
}

.mode-switcher__label {
  white-space: nowrap;
}
</style>
