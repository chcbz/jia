import { useApiStore } from '@/stores/api'

export default [
  {
    path: '/oauth2/callback',
    name: 'OAuthCallback',
    component: {
      async beforeRouteEnter (to, from, next) {
        try {
          const code = to.query.code
          if (!code) throw new Error('No authorization code provided')
          const apiStore = useApiStore()
          await apiStore.exchangeCodeForToken(code)
          await apiStore.getUserInfo()
          next(to.query.state || '/')
        } catch (error) {
          console.error('OAuth callback error:', error)
          next('/')
        }
      },
      render: () => null
    },
    meta: {
      title: 'app.task_list',
      showInMenu: false
    }
  },
  {
    path: '/',
    name: 'Chat',
    component: () => import('@/components/Chat'),
    meta: {
      title: 'chat.title',
      showInMenu: true,
      menuOrder: 1
    }
  },
  {
    path: '/task',
    name: 'TaskIndex',
    component: () => import('@/components/TaskIndex'),
    meta: {
      title: 'app.title',
      showInMenu: true,
      menuOrder: 2
    }
  },
  {
    path: '/list',
    name: 'TaskList',
    component: () => import('@/components/TaskList'),
    meta: {
      title: 'app.task_list',
      showInMenu: false
    }
  },
  {
    path: '/history',
    name: 'TaskHistory',
    component: () => import('@/components/TaskHistory'),
    meta: {
      title: 'app.task_history',
      showInMenu: false
    }
  },
  {
    path: '/add',
    name: 'TaskAdd',
    component: () => import('@/components/TaskAdd'),
    meta: {
      title: 'app.task_add',
      showInMenu: false
    }
  },
  {
    path: '/pay',
    name: 'GiftPay',
    component: () => import('@/components/GiftPay'),
    meta: {
      title: 'gift.title',
      showInMenu: false,
      menuOrder: 3
    }
  },
  {
    path: '/order/list',
    name: 'OrderList',
    component: () => import('@/components/OrderList'),
    meta: {
      title: 'gift.order_list',
      showInMenu: false,
      menuOrder: 4
    }
  },
  {
    path: '/vote',
    name: 'VoteTick',
    component: () => import('@/components/VoteTick'),
    meta: {
      title: 'vote.title',
      showInMenu: false,
      menuOrder: 5
    }
  },
  {
    path: '/phrase',
    name: 'Phrase',
    component: () => import('@/components/Phrase'),
    meta: {
      title: 'phrase.title',
      showInMenu: true,
      menuOrder: 6
    }
  },
  {
    path: '/dwz',
    name: 'ShortLink',
    component: () => import('@/components/ShortLink'),
    meta: {
      title: 'dwz.title',
      showInMenu: true,
      menuOrder: 7
    }
  },
  {
    path: '/hello',
    name: 'HellowList',
    component: () => import('@/components/HelloWorld')
  },
]
