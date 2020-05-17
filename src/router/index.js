export default [
  {
    path: '/',
    name: 'TaskIndex',
    component: resolve => require(['@/components/TaskIndex'], resolve),
    meta: {
      title: 'app.title'
    }
  },
  {
    path: '/list',
    name: 'TaskList',
    component: resolve => require(['@/components/TaskList'], resolve),
    meta: {
      title: 'app.task_list'
    }
  },
  {
    path: '/history',
    name: 'TaskHistory',
    component: resolve => require(['@/components/TaskHistory'], resolve),
    meta: {
      title: 'app.task_history'
    }
  },
  {
    path: '/add',
    name: 'TaskAdd',
    component: resolve => require(['@/components/TaskAdd'], resolve),
    meta: {
      title: 'app.task_add'
    }
  },
  {
    path: '/pay',
    name: 'GiftPay',
    component: resolve => require(['@/components/GiftPay'], resolve),
    meta: {
      title: 'gift.title'
    }
  },
  {
    path: '/vote',
    name: 'VoteTick',
    component: resolve => require(['@/components/VoteTick'], resolve),
    meta: {
      title: 'vote.title'
    }
  },
  {
    path: '/phrase',
    name: 'Phrase',
    component: resolve => require(['@/components/Phrase'], resolve),
    meta: {
      title: 'phrase.title'
    }
  },
  {
    path: '/dwz',
    name: 'ShortLink',
    component: resolve => require(['@/components/ShortLink'], resolve),
    meta: {
      title: 'dwz.title'
    }
  },
  {
    path: '/hello',
    name: 'HellowList',
    component: resolve => require(['@/components/HelloWorld'], resolve)
  }
]
