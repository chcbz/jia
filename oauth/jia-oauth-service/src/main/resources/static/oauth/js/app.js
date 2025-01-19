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
            }
            else if (reg.test(result) == false) {
                 $("#errorDiv").text("手机号码不符合规范")
                $("#telephoneNumber").focus();
                fhz = false;
                return false;
            }
            return fhz;
        };
        //点击验证码倒计时
       sc.countdown = 60;

        sc.settime = function (val) {
            if (app.countdown == 0) {
                $("#" + val).removeAttr("disabled");
                $("#" + val).addClass("btn-danger");
                $("#" + val).html("获取验证码");
                app.countdown = 60;
            } else {
                $("#" + val).removeClass("btn-danger");
                $("#" + val).attr("disabled", true);
                $("#" + val).html("重新发送" + app.countdown + "秒");
                app.countdown--;
                setTimeout(function () {
                    app.settime(val)
                }, 1000)
            }
        }

        //获取手机验证码
        sc.createsmsCode = function (smsType) {
            if (!smsType) {
                smsType = 1;
            }
            if (!app.varfilyphone()) return;
            app.settime("phoneCode");
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
                        $.messager.alert('成功提示',"短息已发送，请注意查收!");
                    }
                    else {
                        $("#errorDiv").text("获取失败");
                    }
                },
                error: function () {
                    $("#errorDiv").text("获取失败");
                }
            });
        };
//        验证信息
          sc.varfilysmsCode=function (){
                 var fhz=true;
                 var phone=$("#telephoneNumber").val();
                 var smsCode=$("#telephoneNumberV").val();
                 var data={
                     "phone":phone,
                     "smsCode":smsCode,
                     "smsType":1
                 };
                 $.ajax({
                     type:"get",
                     url:"sms/validate",
                     dataType:"json",
                     async: false,
                     data:data,
                     success:function(msg){
                         if (msg.msg=="ok") {

                         }
                         else {
                             $("#errorDiv").text("手机验证码错误");
                             fhz=false;
                             return false;
                         }
                     },
                     error: function () {
                          $("#errorDiv").text("请联系管理员!");
                         fhz=false;
                         return false;
                     }
                 });
                 return fhz;
             };
    return sc;
})();

