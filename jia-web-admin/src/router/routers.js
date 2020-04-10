import Main from '@/components/main'
// import parentView from '@/components/parent-view'

/**
 * iview-admin中meta除了原生参数外可配置的参数:
 * meta: {
 *  title: { String|Number|Function }
 *         显示在侧边栏、面包屑和标签栏的文字
 *         使用'{{ 多语言字段 }}'形式结合多语言使用，例子看多语言的路由配置;
 *         可以传入一个回调函数，参数是当前路由对象，例子看动态路由和带参路由
 *  hideInBread: (false) 设为true后此级路由将不会出现在面包屑中，示例看QQ群路由配置
 *  hideInMenu: (false) 设为true后在左侧菜单不会显示该页面选项
 *  notCache: (false) 设为true后页面在切换标签后不会缓存，如果需要缓存，无需设置这个字段，而且需要设置页面组件name属性和路由配置的name一致
 *  access: (null) 可访问该页面的权限数组，当前路由设置的权限会影响子路由
 *  icon: (-) 该页面在左侧菜单、面包屑和标签导航处显示的图标，如果是自定义图标，需要在图标名称前加下划线'_'
 *  beforeCloseName: (-) 设置该字段，则在关闭当前tab页时会去'@/router/before-close.js'里寻找该字段名对应的方法，作为关闭前的钩子函数
 * }
 */

export default [
  {
    path: '/login',
    name: 'login',
    meta: {
      title: 'Login - 登录',
      hideInMenu: true,
      pid: ''
    },
    component: () => import('@/view/login/login.vue')
  },
  {
    path: '/register',
    name: 'register',
    meta: {
      title: '注册',
      hideInMenu: true,
      pid: ''
    },
    component: () => import('@/view/login/register.vue')
  },
  {
    path: '/wechatLogin',
    name: 'wechatLogin',
    meta: {
      title: '微信登录',
      hideInMenu: true,
      pid: ''
    },
    component: () => import('@/view/login/wechat-login.vue')
  },
  {
    path: '/',
    name: '_home',
    redirect: '/home',
    component: Main,
    meta: {
      hideInMenu: true,
      notCache: true,
      pid: ''
    },
    children: [
      {
        path: '/home',
        name: 'home',
        meta: {
          hideInMenu: true,
          title: '首页',
          notCache: true,
          icon: 'md-home',
          pid: ''
        },
        component: () => import('@/view/single-page/home')
      }
    ]
  },
  // {
  //   path: '',
  //   name: 'doc',
  //   meta: {
  //     title: '文档',
  //     href: 'https://lison16.github.io/iview-admin-doc/#/',
  //     icon: 'ios-book'
  //   }
  // },
  // {
  //   path: '/join',
  //   name: 'join',
  //   component: Main,
  //   meta: {
  //     hideInBread: true
  //   },
  //   children: [
  //     {
  //       path: 'join_page',
  //       name: 'join_page',
  //       meta: {
  //         icon: '_qq',
  //         title: 'QQ群'
  //       },
  //       component: () => import('@/view/join-page.vue')
  //     }
  //   ]
  // },
  {
    path: '/message',
    name: 'message',
    component: Main,
    meta: {
      hideInBread: true,
      hideInMenu: true,
      pid: ''
    },
    children: [
      {
        path: 'message_page',
        name: 'message_page',
        meta: {
          icon: 'md-notifications',
          title: '消息中心',
          pid: 'system'
        },
        component: () => import('@/view/single-page/message/index.vue')
      }
    ]
  },
  {
    path: '/user',
    name: 'user',
    component: Main,
    meta: {
      title: '个人中心',
      hideInMenu: true,
      pid: 'other'
    },
    children: [
      {
        path: 'update_user',
        name: 'update_user',
        meta: {
          icon: 'md-person',
          title: '更新个人信息',
          pid: 'system'
        },
        component: () => import('@/view/single-page/user/user-config.vue')
      }
    ]
  },
  {
    path: '/org',
    name: 'org',
    component: Main,
    meta: {
      title: '组织机构',
      hideInMenu: true,
      pid: ''
    },
    children: [
      {
        path: 'update_org',
        name: 'update_org',
        meta: {
          icon: 'ios-people',
          title: '更新组织信息',
          pid: 'system'
        },
        component: () => import('@/view/single-page/org/org-config.vue')
      }
    ]
  },
  // {
  //   path: '/components',
  //   name: 'components',
  //   meta: {
  //     icon: 'logo-buffer',
  //     title: '组件'
  //   },
  //   component: Main,
  //   children: [
  //     {
  //       path: 'count_to_page',
  //       name: 'count_to_page',
  //       meta: {
  //         icon: 'md-trending-up',
  //         title: '数字渐变'
  //       },
  //       component: () => import('@/view/components/count-to/count-to.vue')
  //     },
  //     {
  //       path: 'drag_list_page',
  //       name: 'drag_list_page',
  //       meta: {
  //         icon: 'ios-infinite',
  //         title: '拖拽列表'
  //       },
  //       component: () => import('@/view/components/drag-list/drag-list.vue')
  //     },
  //     {
  //       path: 'drag_drawer_page',
  //       name: 'drag_drawer_page',
  //       meta: {
  //         icon: 'md-list',
  //         title: '可拖拽抽屉'
  //       },
  //       component: () => import('@/view/components/drag-drawer')
  //     },
  //     {
  //       path: 'org_tree_page',
  //       name: 'org_tree_page',
  //       meta: {
  //         icon: 'ios-people',
  //         title: '组织结构树'
  //       },
  //       component: () => import('@/view/components/org-tree')
  //     },
  //     {
  //       path: 'tree_table_page',
  //       name: 'tree_table_page',
  //       meta: {
  //         icon: 'md-git-branch',
  //         title: '树状表格'
  //       },
  //       component: () => import('@/view/components/tree-table/index.vue')
  //     },
  //     {
  //       path: 'cropper_page',
  //       name: 'cropper_page',
  //       meta: {
  //         icon: 'md-crop',
  //         title: '图片裁剪'
  //       },
  //       component: () => import('@/view/components/cropper/cropper.vue')
  //     },
  //     {
  //       path: 'tables_page',
  //       name: 'tables_page',
  //       meta: {
  //         icon: 'md-grid',
  //         title: '多功能表格'
  //       },
  //       component: () => import('@/view/components/tables/tables.vue')
  //     },
  //     {
  //       path: 'split_pane_page',
  //       name: 'split_pane_page',
  //       meta: {
  //         icon: 'md-pause',
  //         title: '分割窗口'
  //       },
  //       component: () => import('@/view/components/split-pane/split-pane.vue')
  //     },
  //     {
  //       path: 'markdown_page',
  //       name: 'markdown_page',
  //       meta: {
  //         icon: 'logo-markdown',
  //         title: 'Markdown编辑器'
  //       },
  //       component: () => import('@/view/components/markdown/markdown.vue')
  //     },
  //     {
  //       path: 'editor_page',
  //       name: 'editor_page',
  //       meta: {
  //         icon: 'ios-create',
  //         title: '富文本编辑器'
  //       },
  //       component: () => import('@/view/components/editor/editor.vue')
  //     },
  //     {
  //       path: 'icons_page',
  //       name: 'icons_page',
  //       meta: {
  //         icon: '_bear',
  //         title: '自定义图标'
  //       },
  //       component: () => import('@/view/components/icons/icons.vue')
  //     }
  //   ]
  // },
  // {
  //   path: '/update',
  //   name: 'update',
  //   meta: {
  //     icon: 'md-cloud-upload',
  //     title: '数据上传'
  //   },
  //   component: Main,
  //   children: [
  //     {
  //       path: 'update_table_page',
  //       name: 'update_table_page',
  //       meta: {
  //         icon: 'ios-document',
  //         title: '上传Csv'
  //       },
  //       component: () => import('@/view/update/update-table.vue')
  //     },
  //     {
  //       path: 'update_paste_page',
  //       name: 'update_paste_page',
  //       meta: {
  //         icon: 'md-clipboard',
  //         title: '粘贴表格数据'
  //       },
  //       component: () => import('@/view/update/update-paste.vue')
  //     }
  //   ]
  // },
  // {
  //   path: '/excel',
  //   name: 'excel',
  //   meta: {
  //     icon: 'ios-stats',
  //     title: 'EXCEL导入导出'
  //   },
  //   component: Main,
  //   children: [
  //     {
  //       path: 'upload-excel',
  //       name: 'upload-excel',
  //       meta: {
  //         icon: 'md-add',
  //         title: '导入EXCEL'
  //       },
  //       component: () => import('@/view/excel/upload-excel.vue')
  //     },
  //     {
  //       path: 'export-excel',
  //       name: 'export-excel',
  //       meta: {
  //         icon: 'md-download',
  //         title: '导出EXCEL'
  //       },
  //       component: () => import('@/view/excel/export-excel.vue')
  //     }
  //   ]
  // },
  // {
  //   path: '/tools_methods',
  //   name: 'tools_methods',
  //   meta: {
  //     hideInBread: true
  //   },
  //   component: Main,
  //   children: [
  //     {
  //       path: 'tools_methods_page',
  //       name: 'tools_methods_page',
  //       meta: {
  //         icon: 'ios-hammer',
  //         title: '工具方法',
  //         beforeCloseName: 'before_close_normal'
  //       },
  //       component: () => import('@/view/tools-methods/tools-methods.vue')
  //     }
  //   ]
  // },
  // // {
  // //   path: '/i18n',
  // //   name: 'i18n',
  // //   meta: {
  // //     hideInBread: true
  // //   },
  // //   component: Main,
  // //   children: [
  // //     {
  // //       path: 'i18n_page',
  // //       name: 'i18n_page',
  // //       meta: {
  // //         icon: 'md-planet',
  // //         title: 'i18n - {{ i18n_page }}'
  // //       },
  // //       component: () => import('@/view/i18n/i18n-page.vue')
  // //     }
  // //   ]
  // // },
  // {
  //   path: '/error_store',
  //   name: 'error_store',
  //   meta: {
  //     hideInBread: true
  //   },
  //   component: Main,
  //   children: [
  //     {
  //       path: 'error_store_page',
  //       name: 'error_store_page',
  //       meta: {
  //         icon: 'ios-bug',
  //         title: '错误收集'
  //       },
  //       component: () => import('@/view/error-store/error-store.vue')
  //     }
  //   ]
  // },
  // {
  //   path: '/error_logger',
  //   name: 'error_logger',
  //   meta: {
  //     hideInBread: true,
  //     hideInMenu: true
  //   },
  //   component: Main,
  //   children: [
  //     {
  //       path: 'error_logger_page',
  //       name: 'error_logger_page',
  //       meta: {
  //         icon: 'ios-bug',
  //         title: '错误收集'
  //       },
  //       component: () => import('@/view/single-page/error-logger.vue')
  //     }
  //   ]
  // },
  // {
  //   path: '/directive',
  //   name: 'directive',
  //   meta: {
  //     hideInBread: true
  //   },
  //   component: Main,
  //   children: [
  //     {
  //       path: 'directive_page',
  //       name: 'directive_page',
  //       meta: {
  //         icon: 'ios-navigate',
  //         title: '指令'
  //       },
  //       component: () => import('@/view/directive/directive.vue')
  //     }
  //   ]
  // },
  // {
  //   path: 'role_control',
  //   name: 'role_control',
  //   meta: {
  //     icon: 'md-flag',
  //     title: '权限控制',
  //   },
  //   component: () => import('@/view/system/role-control.vue')
  // },
  // {
  //   path: '/multilevel',
  //   name: 'multilevel',
  //   meta: {
  //     access: [110],
  //     icon: 'md-menu',
  //     title: '多级菜单'
  //   },
  //   component: Main,
  //   children: [
  //     {
  //       path: 'level_2_1',
  //       name: 'level_2_1',
  //       meta: {
  //         icon: 'md-funnel',
  //         title: '二级-1'
  //       },
  //       component: () => import('@/view/multilevel/level-2-1.vue')
  //     },
  //     {
  //       path: 'level_2_2',
  //       name: 'level_2_2',
  //       meta: {
  //         icon: 'md-funnel',
  //         showAlways: true,
  //         title: '二级-2'
  //       },
  //       children: [
  //         {
  //           path: 'level_2_2_1',
  //           name: 'level_2_2_1',
  //           meta: {
  //             icon: 'md-funnel',
  //             title: '三级'
  //           },
  //           component: () => import('@/view/multilevel/level-2-2/level-2-2-1.vue')
  //         },
  //         {
  //           path: 'level_2_2_2',
  //           name: 'level_2_2_2',
  //           meta: {
  //             icon: 'md-funnel',
  //             title: '三级'
  //           },
  //           component: () => import('@/view/multilevel/level-2-2/level-2-2-2.vue')
  //         }
  //       ]
  //     },
  //     {
  //       path: 'level_2_3',
  //       name: 'level_2_3',
  //       meta: {
  //         icon: 'md-funnel',
  //         title: '二级-3'
  //       },
  //       component: () => import('@/view/multilevel/level-2-3.vue')
  //     }
  //   ]
  // },

  /* 总后台管理 */
  {
    path: '/generalBackstage',
    name: 'generalBackstage',
    meta: {
      icon: 'ios-key',
      title: '总后台管理',
      pid: ''
    },
    component: Main,
    children: [
      {
        path: 'action_system',
        name: 'action_system',
        meta: {
          icon: 'md-flag',
          title: '权限控制',
          pid: 'generalBackstage'
        },
        component: () => import('@/view/system/action-system.vue')
      },
      {
        path: 'dict_system',
        name: 'dict_system',
        meta: {
          icon: 'md-book',
          title: '字典管理',
          pid: 'generalBackstage'
        },
        component: () => import('@/view/system/dict-system.vue')
      },
      {
        path: 'main_role_system',
        name: 'main_role_system',
        meta: {
          icon: 'ios-contacts',
          title: '角色管理',
          pid: 'generalBackstage'
        },
        component: () => import('@/view/system/main-role-system.vue')
      },
      {
        path: 'file_system',
        name: 'file_system',
        meta: {
          icon: 'md-albums',
          title: '静态文件管理',
          pid: 'generalBackstage'
        },
        component: () => import('@/view/system/file-system.vue')
      },
      {
        path: 'notice_system',
        name: 'notice_system',
        meta: {
          icon: 'md-megaphone',
          title: '公告管理',
          pid: 'generalBackstage'
        },
        component: () => import('@/view/system/notice-system.vue')
      },
      {
        path: 'notice_editor/:id',
        name: 'notice_editor',
        meta: {
          icon: 'ios-create',
          title: route => `{{ notice_editor }}-${route.params.id}`,
          hideInMenu: true,
          pid: 'generalBackstage'
        },
        component: () => import('@/view/system/chi/notice-editor.vue')
      },
      {
        path: 'notice_content/:id',
        name: 'notice_content',
        meta: {
          icon: 'ios-book',
          title: route => `{{ notice_content }}-${route.params.id}`,
          hideInMenu: true,
          pid: 'generalBackstage'
        },
        component: () => import('@/view/system/chi/notice-content.vue')
      }
    ]
  },

  /* 系统设置 */
  {
    path: '/system',
    name: 'system',
    meta: {
      icon: 'md-settings',
      title: '系统设置',
      pid: ''
    },
    component: Main,
    children: [
      {
        path: 'user_system',
        name: 'user_system',
        meta: {
          icon: 'md-people',
          title: '用户管理',
          pid: 'system'
        },
        component: () => import('@/view/system/user-system.vue')
      },
      {
        path: 'role_system',
        name: 'role_system',
        meta: {
          icon: 'md-person-add',
          title: '角色管理',
          pid: 'system'
        },
        component: () => import('@/view/system/role-system.vue')
      },
      {
        path: 'group_system',
        name: 'group_system',
        meta: {
          icon: 'md-git-compare',
          title: '用户组管理',
          pid: 'system'
        },
        component: () => import('@/view/system/group-system.vue')
      },
      {
        path: 'oauth_client_config',
        name: 'oauth_client_config',
        meta: {
          icon: 'ios-easel',
          title: '配置客户端信息',
          pid: 'system'
        },
        component: () => import('@/view/system/oauth-client-config.vue')
      }
      // {
      //   path: 'org_system',
      //   name: 'org_system',
      //   meta: {
      //     icon: 'md-bonfire',
      //     title: '组织管理'
      //   },
      //   component: () => import('@/view/components/org-tree')
      //   // component: () => import('@/view/system/org-system.vue')
      // }
    ]
  },
  // 工作流
  {
    path: '/work_flow',
    name: 'work_flow',
    meta: {
      icon: 'md-locate',
      title: '工作流管理',
      pid: ''
    },
    component: Main,
    children: [
      {
        path: 'deploy_work_flow',
        name: 'deploy_work_flow',
        meta: {
          // access: ['workflow_complete'],
          icon: 'ios-key',
          title: '工作流部署',
          pid: 'workflow'
        },
        component: () => import('@/view/work-flow/deploy-work-flow.vue')
      },
      {
        path: 'work_flow_list',
        name: 'work_flow_list',
        meta: {
          // access: ['workflow_complete'],
          icon: 'ios-paper',
          title: '工作流部署列表',
          pid: 'workflow'
        },
        component: () => import('@/view/work-flow/work-flow-list.vue')
      },
      {
        path: 'work_flow_resource_list/:id',
        name: 'work_flow_resource_list',
        meta: {
          icon: 'ios-paper',
          title: route => `{{ work_flow_resource_list }}-${route.query.name}`,
          notCache: true,
          hideInMenu: true,
          pid: 'workflow'
        },
        component: () => import('@/view/work-flow/resource/resourceList')
      },
      {
        path: 'work_flow_definition_list',
        name: 'work_flow_definition_list',
        meta: {
          // access: ['workflow_complete'],
          icon: 'md-git-commit',
          title: '工作流定义列表',
          pid: 'workflow'
        },
        component: () => import('@/view/work-flow/work-flow-definition-list.vue')
      },
      {
        path: 'update_deploy_work_flow/:deploymentId',
        name: 'update_deploy_work_flow',
        meta: {
          icon: 'ios-paper',
          title: route => `{{ update_deploy_work_flow }}-${route.query.name}`,
          notCache: true,
          hideInMenu: true,
          pid: 'workflow'
        },
        component: () => import('@/view/work-flow/update-deploy-work-flow')
      },
      {
        path: 'work_flow_instance_list',
        name: 'work_flow_instance_list',
        meta: {
          // access: ['workflow_complete'],
          icon: 'ios-git-compare',
          title: '工作流实例列表',
          pid: 'workflow'
        },
        component: () => import('@/view/work-flow/work-flow-instance-list.vue')
      },
      {
        path: 'work_flow_history_list/:id',
        name: 'work_flow_history_list',
        meta: {
          icon: 'md-checkmark-circle',
          title: route => `{{ work_flow_history_list }}-${route.query.businessKey}`,
          notCache: true,
          hideInMenu: true,
          pid: 'workflow'
        },
        component: () => import('@/view/work-flow/workflow/historyList')
      }
    ]
  },

  /* 短信管理 */
  {
    path: '/sms',
    name: 'sms',
    meta: {
      icon: 'md-mail',
      title: '短信管理',
      pid: ''
    },
    component: Main,
    children: [
      {
        path: 'sms_index',
        name: 'sms_index',
        meta: {
          // access: ['sms_receive', 'sms_config_get'],
          icon: 'md-mail',
          title: '短信首页',
          pid: 'sms'
        },
        component: () => import('@/view/sms/sms-index')
      },
      {
        path: 'sms_send_list',
        name: 'sms_send_list',
        meta: {
          // access: ['sms_receive', 'sms_config_get'],
          icon: 'md-send',
          title: '短信发送列表',
          pid: 'sms'
        },
        component: () => import('@/view/sms/sms-send-list')
      },
      {
        path: 'sms_sendBatch',
        name: 'sms_sendBatch',
        meta: {
          // access: ['sms_receive'],
          icon: 'md-text',
          title: '短信群发',
          pid: 'sms'
        },
        component: () => import('@/view/sms/sms-sendBatch')
      },
      {
        path: 'sms_package',
        name: 'sms_package',
        meta: {
          // access: ['sms_receive'],
          icon: 'md-basket',
          title: '购买短信套餐',
          pid: 'sms'
        },
        component: () => import('@/view/sms/sms-package')
      },
      {
        path: 'sms_buy_list',
        name: 'sms_buy_list',
        meta: {
          // access: ['sms_receive'],
          icon: 'md-calendar',
          title: '短信购买情况',
          pid: 'sms'
        },
        component: () => import('@/view/sms/sms-buy-list')
      },
      {
        path: 'sms_template_list',
        name: 'sms_template_list',
        meta: {
          // access: ['sms_receive'],
          icon: 'md-copy',
          title: '短信模板列表',
          pid: 'sms'
        },
        component: () => import('@/view/sms/sms-template-list')
      },
      {
        path: 'sms_config',
        name: 'sms_config',
        meta: {
          // access: ['sms_receivesms_receive'],
          icon: 'md-options',
          title: '短信配置信息',
          pid: 'sms'
        },
        component: () => import('@/view/sms/sms-config')
      },
      {
        path: 'sms_reply_list/:msgid',
        name: 'sms_reply_list',
        meta: {
          icon: 'ios-send',
          title: route => `{{ sms_reply_list }}-${route.params.msgid}`,
          hideInMenu: true,
          pid: 'sms'
        },
        component: () => import('@/view/sms/sms-reply-list')
      }
    ]
  },

  /* 微信公众号管理 */
  {
    path: '/wechat',
    name: 'wechat',
    meta: {
      icon: 'ios-chatbubbles',
      title: '微信',
      pid: ''
    },
    component: Main,
    children: [
      {
        path: 'wechat_mpList',
        name: 'wechat_mpList',
        meta: {
          icon: 'ios-text',
          title: '公众号列表',
          pid: 'wechat'
        },
        component: () => import('@/view/wechat/wechat-mpList')
      },
      {
        path: 'wechat_menu',
        name: 'wechat_menu',
        meta: {
          icon: 'ios-menu',
          title: '公众号管理',
          pid: 'wechat'
        },
        component: () => import('@/view/wechat/wechat-menu')
      },
      {
        path: 'wechat_user/:appid',
        name: 'wechat_user',
        meta: {
          icon: 'md-people',
          title: route => `{{ wechat_user }}-${route.query.name}`,
          hideInMenu: true,
          pid: 'wechat'
        },
        component: () => import('@/view/wechat/supervise/wechat-user')
      },
      {
        path: 'wechat_diymenu/:appid',
        name: 'wechat_diymenu',
        meta: {
          icon: 'ios-menu',
          title: route => `{{ wechat_diymenu }}-${route.query.name}`,
          hideInMenu: true,
          pid: 'wechat'
        },
        component: () => import('@/view/wechat/supervise/wechat-menu')
      },
      {
        path: 'wechat_material/:appid',
        name: 'wechat_material',
        meta: {
          icon: 'logo-buffer',
          title: route => `{{ wechat_material }}-${route.query.name}`,
          hideInMenu: true,
          pid: 'wechat'
        },
        component: () => import('@/view/wechat/supervise/wechat-material')
      },
      {
        path: 'wechat_scanPay/:productId',
        name: 'wechat_scanPay',
        meta: {
          icon: 'logo-usd',
          title: route => `{{ wechat_scanPay }}-${route.query.productId}`,
          hideInMenu: true,
          pid: 'wechat'
        },
        component: () => import('@/view/wechat/buy/wechat-scanPay')
      },
      {
        path: 'wechat_payInfoList',
        name: 'wechat_payInfoList',
        meta: {
          icon: 'logo-usd',
          title: '微信支付管理',
          pid: 'wechat'
        },
        component: () => import('@/view/wechat/wechat_payInfoList')
      }
    ]
  },

  /* 表格管理 */
  {
    path: '/cms',
    name: 'cms',
    meta: {
      icon: 'ios-keypad',
      title: 'cms',
      pid: ''
    },
    component: Main,
    children: [
      {
        path: 'cms_table_menu',
        name: 'cms_table_menu',
        meta: {
          icon: 'ios-menu',
          title: '表格管理',
          pid: 'cms'
        },
        component: () => import('@/view/cms/table/cms-table-menu')
      },
      {
        path: 'cms_config',
        name: 'cms_config',
        meta: {
          // hideInMenu: true,
          icon: 'md-options',
          title: '表单配置',
          pid: 'cms'
        },
        component: () => import('@/view/cms/table/cms-config')
      },
      {
        path: 'cms_table_list',
        name: 'cms_table_list',
        meta: {
          icon: 'ios-list-box-outline',
          title: '表格列表',
          pid: 'cms'
        },
        component: () => import('@/view/cms/table/cms-table-list')
      },
      {
        path: 'cms_file_list',
        name: 'cms_file_list',
        meta: {
          icon: 'ios-list-box-outline',
          title: '素材列表',
          pid: 'cms'
        },
        component: () => import('@/view/cms/table/cms-file-list')
      },
      {
        path: 'cms_table_column_list/:tableId',
        name: 'cms_table_column_list',
        meta: {
          title: route => `{{ cms_table_column_list }}-${route.query.name}`,
          hideInMenu: true,
          pid: 'cms'
        },
        component: () => import('@/view/cms/table/tables/table-column-list')
      },
      {
        path: 'cms_table_row_list/:name',
        name: 'cms_table_row_list',
        meta: {
          title: route => `{{ cms_table_row_list }}-${route.params.name}`,
          hideInMenu: true,
          pid: 'cms'
        },
        component: () => import('@/view/cms/table/tables/table-row-list')
      }
    ]
  },

  /* 车辆管理 */
  {
    path: '/car',
    name: 'car',
    meta: {
      icon: 'ios-car',
      title: '车辆管理',
      pid: ''
    },
    component: Main,
    children: [
      // {
      //   path: 'car_brandList',
      //   name: 'car_brandList',
      //   meta: {
      //     icon: 'ios-contrast',
      //     title: '车辆品牌列表'
      //   },
      //   component: () => import('@/view/car/car-brandList')
      // },
      // {
      //   path: 'car_brandMfList',
      //   name: 'car_brandMfList',
      //   meta: {
      //     icon: 'ios-contacts',
      //     title: '车辆制造商列表'
      //   },
      //   component: () => import('@/view/car/car-brandMfList')
      // },
      // {
      //   path: 'car_brandAudiList',
      //   name: 'car_brandAudiList',
      //   meta: {
      //     icon: 'ios-ribbon',
      //     title: '车辆系列列表'
      //   },
      //   component: () => import('@/view/car/car-brandAudiList')
      // },
      // {
      //   path: 'car_brandVersionList',
      //   name: 'car_brandVersionList',
      //   meta: {
      //     icon: 'logo-snapchat',
      //     title: '车辆型号列表'
      //   },
      //   component: () => import('@/view/car/car-brandVersionList')
      // },
      {
        path: 'car_system',
        name: 'car_system',
        meta: {
          icon: 'md-car',
          title: '车辆综合管理',
          pid: 'car'
        },
        component: () => import('@/view/car/car-system')
      }
    ]
  },

  /* 服务器管理 */
  {
    path: '/server',
    name: 'server',
    meta: {
      icon: 'ios-desktop',
      title: 'server',
      pid: ''
    },
    component: Main,
    children: [
      {
        path: 'server_list',
        name: 'server_list',
        meta: {
          icon: 'md-easel',
          title: '服务器列表',
          pid: 'server'
        },
        component: () => import('@/view/server/server-list')
      },
      {
        path: 'server_app_list/:id',
        name: 'server_app_list',
        meta: {
          title: route => `{{ server_app_list }}-${route.query.serverName}`,
          hideInMenu: true,
          pid: 'server'
        },
        component: () => import('@/view/server/serverDetailed/server-app-list')
      },
      {
        path: 'server_detailed/:id',
        name: 'server_detailed',
        meta: {
          icon: 'md-laptop',
          title: '服务器详细信息',
          hideInMenu: true,
          pid: 'serverDetailed'
        },
        component: () => import('@/view/server/serverDetailed/server-detailed')
      },
      {
        path: 'server_ldapGroup/:id',
        name: 'server_ldapGroup',
        meta: {
          icon: 'md-people',
          title: 'LDAP账户组列表',
          hideInMenu: true,
          pid: 'serverDetailed'
        },
        component: () => import('@/view/server/serverDetailed/server-ldapGroup')
      },
      {
        path: 'server_ldapAccount/:id',
        name: 'server_ldapAccount',
        meta: {
          icon: 'md-person-add',
          title: 'LDAP系统账户列表',
          hideInMenu: true,
          pid: 'serverDetailed'
        },
        component: () => import('@/view/server/serverDetailed/server-ldapAccout')
      },
      {
        path: 'server_smbVdir/:id',
        name: 'server_smbVdir',
        meta: {
          icon: 'md-folder',
          title: 'Samba虚拟目录列表',
          hideInMenu: true,
          pid: 'serverDetailed'
        },
        component: () => import('@/view/server/serverDetailed/server-smbVdir')
      },
      {
        path: 'domain_list',
        name: 'domain_list',
        meta: {
          icon: 'ios-disc',
          title: '域名列表',
          pid: 'server'
        },
        component: () => import('@/view/server/domain-list')
      }
    ]
  },

  /* 单页面应用 */
  {
    path: '/materials',
    name: 'materials',
    meta: {
      icon: 'md-copy',
      title: '单页面应用',
      pid: ''
    },
    component: Main,
    children: [
      {
        path: 'materials_newsList',
        name: 'materials_newsList',
        meta: {
          icon: 'md-list-box',
          title: '文章管理',
          pid: 'materials'
        },
        component: () => import('@/view/materials/news-system')
      },
      {
        path: 'news_editor/:id',
        name: 'news_editor',
        meta: {
          icon: 'ios-create',
          title: route => `{{ news_editor }}-${route.params.id}`,
          hideInMenu: true,
          pid: 'materials'
        },
        component: () => import('@/view/materials/chi/news-editor.vue')
      },
      {
        path: 'news_content/:id',
        name: 'news_content',
        meta: {
          icon: 'ios-book',
          title: route => `{{ news_content }}-${route.params.id}`,
          hideInMenu: true,
          pid: 'materials'
        },
        component: () => import('@/view/materials/chi/news-content.vue')
      }
    ]
  },

  {
    path: '/argu',
    name: 'argu',
    meta: {
      hideInMenu: true,
      pid: ''
    },
    component: Main,
    children: [
      {
        path: 'params/:id',
        name: 'params',
        meta: {
          icon: 'md-flower',
          title: route => `{{ params }}-${route.params.id}`,
          notCache: true,
          beforeCloseName: 'before_close_normal',
          pid: ''
        },
        component: () => import('@/view/argu-page/params.vue')
      },
      {
        path: 'query',
        name: 'query',
        meta: {
          icon: 'md-flower',
          title: route => `{{ query }}-${route.query.id}`,
          notCache: true,
          pid: ''
        },
        component: () => import('@/view/argu-page/query.vue')
      }
    ]
  },
  {
    path: '/401',
    name: 'error_401',
    meta: {
      hideInMenu: true,
      pid: ''
    },
    component: () => import('@/view/error-page/401.vue')
  },
  {
    path: '/500',
    name: 'error_500',
    meta: {
      hideInMenu: true,
      pid: ''
    },
    component: () => import('@/view/error-page/500.vue')
  },
  {
    path: '*',
    name: 'error_404',
    meta: {
      hideInMenu: true,
      pid: ''
    },
    component: () => import('@/view/error-page/404.vue')
  }
]
