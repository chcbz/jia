var app = (function () {
    var sc = {};
    //设置localstorage
    sc.Storageset = function (name, val) {
        localStorage.setItem(name, JSON.stringify(val));
    }
    //查询localstorage
    sc.Storageget = function (name) {
        return JSON.parse(localStorage.getItem(name));
    }
    //增加localstorage
    sc.Storageadd = function (name, addVal) {
        var oldVal = Storage.get(name);
        var newVal = oldVal.concat(addVal);
        gu.Storageset(name, newVal);
    }
    //删除localstorage
    sc.Storagedel = function (name) {
        localStorage.removeItem(name);
    }
    //获取地址栏参数的方法
    sc.getQueryString = function (name) {
        var reg = new RegExp("(^|&)" + name + "=([^&]*)(&|$)", "i");
        var r = window.location.search.substr(1).match(reg);
        if (r != null) return unescape(r[2]);
        return null;
    };

    sc.varfilyphone = function () {
        var fhz = true;
        var reg = /^(13\d|14[579]|15[^4\D]|17[^49\D]|18\d)\d{8}$/; //验证规则
        var result = $("#telephoneNumber").val();
        if (result == "") {
            $("#errorDiv").text("手机号码不能为空")
            $("#telephoneNumber").focus();
            fhz = false;
            return false;
        } else if (reg.test(result) == false) {
            $("#errorDiv").text("手机号码不符合规范")
            $("#telephoneNumber").focus();
            fhz = false;
            return false;
        }
        return fhz;
    };
    //点击验证码倒计时
    sc.countdown = 60;
    sc.countdownTimer = null;
    sc.countdownEndTime = 0;

    sc.settime = function (val) {
        // 如果已有计时器在运行，则先清除它
        if (sc.countdownTimer) {
            clearTimeout(sc.countdownTimer);
        }
        
        // 获取当前时间
        var now = new Date().getTime();
        
        // 如果是第一次启动倒计时或者页面从不可见变为可见
        if (sc.countdownEndTime === 0) {
            sc.countdownEndTime = now + sc.countdown * 1000;
        }
        
        // 计算剩余时间
        var remaining = Math.round((sc.countdownEndTime - now) / 1000);
        
        if (remaining <= 0) {
            // 倒计时结束
            $("#" + val).removeAttr("disabled");
            $("#" + val).addClass("btn-danger");
            $("#" + val).removeClass("btn-default");
            $("#" + val).html("获取验证码");
            sc.countdown = 60;
            sc.countdownEndTime = 0;
        } else {
            // 更新按钮显示
            $("#" + val).removeClass("btn-danger");
            $("#" + val).attr("disabled", true);
            $("#" + val).html("重发" + remaining + "秒");
            
            // 继续倒计时
            sc.countdownTimer = setTimeout(function () {
                sc.settime(val);
            }, 100);
        }
    };

    // 页面可见性变化时的处理
    document.addEventListener("visibilitychange", function() {
        // 当页面变为可见并且有正在进行的倒计时时，重新计算剩余时间
        if (!document.hidden && sc.countdownEndTime > 0) {
            // 清除现有计时器
            if (sc.countdownTimer) {
                clearTimeout(sc.countdownTimer);
            }
            // 重新启动倒计时以确保准确性
            sc.settime("phoneCode");
        }
    });

    //获取手机验证码
    sc.createsmsCode = function (smsType) {
        if (!smsType) {
            smsType = 1;
        }
        if (!app.varfilyphone()) return;
        
        // 显示获取验证码的加载状态
        var phoneCodeBtn = $("#phoneCode");
        var originalText = phoneCodeBtn.text();
        phoneCodeBtn.prop('disabled', true);
        phoneCodeBtn.removeClass('btn-danger');
        phoneCodeBtn.addClass('btn-default');
        phoneCodeBtn.text('发送中...');
        
        // 重置倒计时结束时间
        sc.countdownEndTime = 0;
        var phone = $("#telephoneNumber").val();
        var data = {
            "phone": phone,
            "smsType": smsType
        };
        $.ajax({
            type: "get",
            url: "sms/gen",
            dataType: "json",
//                async: false,
            data: data,
            success: function (msg) {
                if (msg.msg == "ok") {
                    $("#errorDiv").text("");
                    $.messager.alert('成功提示', "短信已发送，请注意查收!");
                    // 启动倒计时
                    app.settime("phoneCode");
                } else {
                    $("#errorDiv").text("获取失败");
                    // 恢复按钮状态
                    phoneCodeBtn.prop('disabled', false);
                    phoneCodeBtn.addClass('btn-danger');
                    phoneCodeBtn.removeClass('btn-default');
                    phoneCodeBtn.text(originalText);
                }
            },
            error: function () {
                $("#errorDiv").text("获取失败");
                // 恢复按钮状态
                phoneCodeBtn.prop('disabled', false);
                phoneCodeBtn.addClass('btn-danger');
                phoneCodeBtn.removeClass('btn-default');
                phoneCodeBtn.text(originalText);
            }
        });
    };
//        验证信息
    sc.varfilysmsCode = function () {
        var fhz = true;
        var phone = $("#telephoneNumber").val();
        var smsCode = $("#telephoneNumberV").val();
        var data = {
            "phone": phone,
            "smsCode": smsCode,
            "smsType": 1
        };
        $.ajax({
            type: "get",
            url: "sms/validate",
            dataType: "json",
            async: false,
            data: data,
            success: function (msg) {
                if (msg.msg == "ok") {

                } else {
                    $("#errorDiv").text("手机验证码错误");
                    fhz = false;
                    return false;
                }
            },
            error: function () {
                $("#errorDiv").text("请联系管理员!");
                fhz = false;
                return false;
            }
        });
        return fhz;
    };
    return sc;
})();