<template>
  <div class="app-container">
    <var-app-bar
      :title="title"
      :left-arrow="leftOptions.showBack"
      @click-left="$router.back()"
    >
      <template #left>
        <var-icon 
          v-if="!leftOptions.showBack"
          name="menu"
          @click.stop="toggleMenu"
          class="menu-icon"
        />
      </template>
      <template #right>
        <var-icon 
          v-if="showMore"
          name="dots-vertical"
          @click.stop="handleMoreClick"
          class="more-icon"
        />
      </template>
    </var-app-bar>

    <var-action-sheet 
      v-if="actionMenu.length > 0"
      :actions="actionMenu"
      v-model="showActionMenu"
      @select="handleMenuSelect"
    />

    <!-- <var-loading v-model="isLoading" type="circle" color="var(--color-primary)"/> -->

    <side-menu v-model="showSideMenu" style="height: 0px;"/>

    <div class="app-content" :class="{'show-menu': showSideMenu}">
      <router-view/>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { 
  AppBar as VarAppBar, 
  Loading as VarLoading, 
  Icon as VarIcon,
  Menu as VarMenu,
  ActionSheet as VarActionSheet
} from '@varlet/ui'
import SideMenu from '@/components/SideMenu'
import { useGlobalStore } from '@/stores/global'
import { useUtilStore } from '@/stores/util'

const globalStore = useGlobalStore()
const utilStore = useUtilStore()

const showActionMenu = ref(false)
const actionMenu = ref([])

const toggleMenu = () => {
  globalStore.toggleSideMenu()
}

const isLoading = computed(() => utilStore.isLoading)
const leftOptions = computed(() => ({
  showBack: globalStore.showBack
}))
const title = computed(() => globalStore.title)
const showSideMenu = computed({
  get: () => globalStore.showSideMenu,
  set: (value) => { globalStore.showSideMenu = value }
})
const showMore = computed(() => globalStore.showMore)

const updateActionMenu = (menu) => {
  actionMenu.value = Object.keys(menu).map(key => ({
    name: key,
    text: menu[key]
  }))
}

const handleMoreClick = () => {
  // 直接触发右侧边栏显示
  globalStore.toggleRightSidebar()
}

const handleMenuSelect = (action) => {
  globalStore.toMenu({
    key: action.name,
    event: null
  })
  showActionMenu.value = false
}

watch(() => globalStore.menu, (newMenu) => {
  updateActionMenu(newMenu)
}, { deep: true })
</script>

<style>
html, body {
  height: 100%;
  width: 100%;
  margin: 0;
  overflow-x: hidden;
  font-family: var(--font-family);
  color: var(--color-text);
  background-color: var(--color-body);
}

.app-container {
  display: flex;
  flex-direction: column;
  height: 100vh;
}

.app-content {
  flex: 1;
  min-height: 0;
  overflow: auto;
  transition: margin-left 0.3s;
}

.menu-icon {
  margin-right: 12px;
  cursor: pointer;
}

.more-icon {
  margin-left: 12px;
  cursor: pointer;
}

@media (min-width: 768px) {
  .app-content {
    transition: margin-left 0.3s;
  }
  
  .app-content.show-menu {
    margin-left: 250px;
  }
}
</style>
