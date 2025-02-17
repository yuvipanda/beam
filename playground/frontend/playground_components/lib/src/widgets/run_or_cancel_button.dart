/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import 'package:easy_localization/easy_localization.dart';
import 'package:flutter/widgets.dart';

import '../controllers/playground_controller.dart';
import '../models/toast.dart';
import '../models/toast_type.dart';
import '../playground_components.dart';
import 'run_button.dart';

class RunOrCancelButton extends StatelessWidget {
  final VoidCallback? beforeCancel;
  final VoidCallback? onComplete;
  final VoidCallback? beforeRun;
  final PlaygroundController playgroundController;

  const RunOrCancelButton({
    required this.playgroundController,
    this.beforeCancel,
    this.onComplete,
    this.beforeRun,
  });

  @override
  Widget build(BuildContext context) {
    return RunButton(
      playgroundController: playgroundController,
      isEnabled: !(playgroundController.selectedExample?.isMultiFile ?? false),
      cancelRun: () async {
        beforeCancel?.call();
        await playgroundController.codeRunner.cancelRun().catchError(
              (_) => PlaygroundComponents.toastNotifier.add(_getErrorToast()),
            );
      },
      runCode: () {
        beforeRun?.call();
        playgroundController.codeRunner.runCode(
          onFinish: onComplete,
        );
      },
    );
  }

  Toast _getErrorToast() {
    return Toast(
      title: 'widgets.runOrCancelButton.notificationTitles.runCode'.tr(),
      description:
          'widgets.runOrCancelButton.notificationTitles.cancelExecution'.tr(),
      type: ToastType.error,
    );
  }
}
