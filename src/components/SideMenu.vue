<template>
  <div>
    <!-- 蒙层 -->
    <div 
      v-if="showSideMenu && isMobile" 
      class="menu-overlay"
      @click="handleOverlayClick"
    ></div>
    
    <var-menu 
      v-model="showSideMenu"
      :placement="menuPlacement"
      :offset-x="menuOffsetX"
      :offset-y="menuOffsetY"
    >
      <template #default>
        <div class="side-menu" :data-show="showSideMenu">
          <div class="menu-header">
            <div style="display: flex; align-items: center;">
              <var-icon 
                name="chevron-left"
                class="close-icon"
                @click="close"
              />
            </div>
          </div>
          
          <div class="menu-items">
            <router-link
              v-for="route in menuRoutes"
              :key="route.path"
              :to="route.path"
              class="menu-item"
              @click="close"
            >
              <var-icon :name="route.meta.icon || 'menu'" />
              <span>{{ $t(route.meta.title) }}</span>
            </router-link>
          </div>
        </div>
      </template>
    </var-menu>
  </div>
</template>

<script setup>
import { computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useWindowSize } from '@vueuse/core'
import { useGlobalStore } from '@/stores/global'

const props = defineProps({
  modelValue: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['update:modelValue', 'close'])

const router = useRouter()
const { width } = useWindowSize()
const globalStore = useGlobalStore()

const isMobile = computed(() => width.value < 768)
const menuPlacement = computed(() => isMobile.value ? 'bottom' : 'right')
const menuOffsetX = computed(() => isMobile.value ? 0 : -16)
const menuOffsetY = computed(() => isMobile.value ? 0 : 56)

const menuRoutes = computed(() => {
  return router.getRoutes().filter(route => route.meta?.title && route.meta?.showInMenu !== false)
})

const close = () => {
  globalStore.toggleSideMenu()
  emit('update:modelValue', false)
  emit('close')
}

const handleOverlayClick = (event) => {
  // 确保点击的是蒙层本身，而不是子元素
  if (event.target.classList.contains('menu-overlay')) {
    close()
  }
}

watch(() => props.modelValue, (newVal) => {
  globalStore.showSideMenu = newVal
  if (!newVal) {
    emit('close')
  }
})

const showSideMenu = computed({
  get() {
    return globalStore.showSideMenu
  },
  set(value) {
    globalStore.showSideMenu = value
  }
})
</script>

<style scoped>
.menu-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100vw;
  height: 100vh;
  background: rgba(0, 0, 0, 0.5);
  z-index: 999;
  animation: fadeIn 0.3s ease;
}

@keyframes fadeIn {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

.side-menu {
  width: 250px;
  height: 100vh;
  background: var(--color-body);
  box-shadow: 0 0 10px rgba(0, 0, 0, 0.1);
  transform: translateX(-100%);
  transition: transform 0.3s ease;
  position: fixed;
  top: 0;
  z-index: 1000;
}

.side-menu[data-show="true"] {
  transform: translateX(0);
}

.menu-header {
  padding: 16px;
  display: flex;
  justify-content: flex-end;
}

.close-icon {
  font-size: 24px;
  cursor: pointer;
  margin-right: 8px;
}

.collapse-text {
  font-size: 14px;
  color: var(--color-text);
}

.menu-items {
  padding: 8px 0;
}

.menu-item {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  color: var(--color-text);
  text-decoration: none;
  transition: all 0.2s ease;
  border-radius: 8px;
  margin: 0 8px;
}

.menu-item:hover {
  background: rgba(var(--color-primary-rgb), 0.1);
  transform: translateX(4px);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

.menu-item i {
  transition: transform 0.2s ease;
}

.menu-item:hover i {
  transform: scale(1.1);
}

.menu-item.router-link-active {
  color: var(--color-primary);
  background: rgba(var(--color-primary-rgb), 0.1);
}

.menu-item i {
  margin-right: 12px;
  font-size: 20px;
}

@media (max-width: 768px) {
  .side-menu {
    width: 60%;
    height: 100vh;
    border-radius: 0;
    top: 0;
    left: 0;
  }
}
</style>
